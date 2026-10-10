package io.popdesk;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The single-instance lock (ADR D-23, S0-AC-11): a session advisory lock with two int keys, held on a
 * database connection of its own, outside the pool, for as long as the app runs. Taken before
 * Flyway ({@link MigrationStart}). A second app waits {@code POPDESK_INSTANCE_LOCK_WAIT} for it and
 * then stops.
 *
 * <p>The two int keys do not meet Flyway's bigint key. {@code popdesk restore} takes the same keys to
 * refuse a database an app is using.
 *
 * <p>If the connection is cut, the lock is gone with it. {@link #held()} turns false until a check,
 * once a second, has taken it again on a new connection. Send loops and scheduled jobs run only while
 * {@link #held()} is true.
 *
 * <p>While the lock cannot be taken again, the reason is logged when it changes and then once a
 * minute, not on every check.
 */
@Component
public class InstanceLock implements DisposableBean {

    /** First key: "popd" in ASCII. */
    public static final int APP_KEY = 0x706f7064;

    /** Second key: the running app. */
    public static final int INSTANCE_KEY = 1;

    static final String WAIT_KEY = "POPDESK_INSTANCE_LOCK_WAIT";

    static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(InstanceLock.class);

    private static final String LOCK_TIMEOUT = "55P03";

    /** How often the same reason for not having the lock is logged again. */
    private static final long REPEAT_NANOS = Duration.ofMinutes(1).toNanos();

    /** Why a check could not take the lock again. */
    private enum Waiting { OTHER_INSTANCE, NO_CONNECTION }

    private final JdbcConnectionDetails database;
    private final Duration wait;
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("popdesk-instance-lock").factory());

    private volatile Connection connection;
    private volatile boolean held;

    // Read and written by the watch thread only.
    private Waiting waiting;
    private long waitingLoggedAt;

    InstanceLock(JdbcConnectionDetails database, Environment environment) {
        this.database = database;
        this.wait = wait(environment);
    }

    private static Duration wait(Environment environment) {
        String value = environment.getProperty(WAIT_KEY);
        if (value == null || value.isBlank()) {
            return DEFAULT_WAIT;
        }
        Duration parsed;
        try {
            parsed = Duration.parse(value.strip());
        } catch (RuntimeException e) {
            throw new IllegalStateException(WAIT_KEY + " is not an ISO-8601 duration such as PT30S.");
        }
        if (parsed.isNegative()) {
            throw new IllegalStateException(WAIT_KEY + " must not be negative.");
        }
        return parsed;
    }

    /** Whether this app holds the lock right now. */
    public boolean held() {
        return held;
    }

    /**
     * Takes the lock, waiting up to {@code POPDESK_INSTANCE_LOCK_WAIT}. Fails with an exception, after
     * logging why, when another instance keeps it.
     */
    void acquire() {
        Connection c = open();
        boolean got;
        try {
            got = lockWithin(c, wait);
        } catch (SQLException e) {
            closeQuietly(c);
            throw new IllegalStateException("Could not take the single-instance lock (SQL state "
                    + e.getSQLState() + ").", e);
        }
        if (!got) {
            closeQuietly(c);
            String message = "Another popdesk instance is running on this database: it holds the single-instance "
                    + "lock, and this instance waited " + wait + " (" + WAIT_KEY + ") for it. popdesk runs as one "
                    + "instance; stop the other one first.";
            log.error(message);
            throw new IllegalStateException(message);
        }
        connection = c;
        held = true;
        log.info("Took the single-instance lock.");
        watch.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
    }

    /** Once a second: if the lock's connection is gone, take the lock again on a new one. */
    private void check() {
        try {
            Connection c = connection;
            if (c != null && c.isValid(2)) {
                return;
            }
            if (held) {
                held = false;
                log.warn("Lost the single-instance lock: its database connection ended. Send loops and scheduled "
                        + "jobs pause until it is taken again.");
            }
            if (c != null) {
                closeQuietly(c);
                connection = null;
            }
            Connection again = open();
            if (lockWithin(again, Duration.ZERO)) {
                connection = again;
                held = true;
                waiting = null;
                log.info("Took the single-instance lock again.");
            } else {
                closeQuietly(again);
                if (logNow(Waiting.OTHER_INSTANCE)) {
                    log.warn("Could not take the single-instance lock again: another popdesk instance holds it. "
                            + "Trying every second; this line comes again once a minute.");
                }
            }
        } catch (SQLException | RuntimeException e) {
            if (logNow(Waiting.NO_CONNECTION)) {
                log.warn("Could not take the single-instance lock again ({}). Trying every second; this line comes "
                        + "again once a minute.", e.getClass().getSimpleName());
            }
        }
    }

    /** Whether to log this reason now: it differs from the last one, or that one was logged a minute ago. */
    private boolean logNow(Waiting reason) {
        long now = System.nanoTime();
        if (reason == waiting && now - waitingLoggedAt < REPEAT_NANOS) {
            return false;
        }
        waiting = reason;
        waitingLoggedAt = now;
        return true;
    }

    /** Blocks up to {@code wait} for the lock. With no wait it only tries once. */
    private static boolean lockWithin(Connection c, Duration wait) throws SQLException {
        if (wait.isZero()) {
            try (PreparedStatement s = c.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
                s.setInt(1, APP_KEY);
                s.setInt(2, INSTANCE_KEY);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("set lock_timeout = " + Math.max(1, wait.toMillis()));
        }
        try (PreparedStatement s = c.prepareStatement("select pg_advisory_lock(?, ?)")) {
            s.setInt(1, APP_KEY);
            s.setInt(2, INSTANCE_KEY);
            s.executeQuery().close();
            return true;
        } catch (SQLException e) {
            if (LOCK_TIMEOUT.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        } finally {
            try (Statement s = c.createStatement()) {
                s.execute("reset lock_timeout");
            }
        }
    }

    private Connection open() {
        Properties properties = new Properties();
        if (database.getUsername() != null) {
            properties.setProperty("user", database.getUsername());
        }
        if (database.getPassword() != null) {
            properties.setProperty("password", database.getPassword());
        }
        properties.setProperty("ApplicationName", "popdesk instance lock");
        properties.setProperty("tcpKeepAlive", "true");
        try {
            Connection c = DriverManager.getConnection(database.getJdbcUrl(), properties);
            c.setAutoCommit(true);
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not connect to the database for the single-instance lock (SQL state "
                    + e.getSQLState() + ").", e);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            log.debug("Closing a lock connection failed ({})", e.getSQLState());
        }
    }

    @Override
    public void destroy() {
        watch.shutdownNow();
        held = false;
        Connection c = connection;
        connection = null;
        if (c != null) {
            closeQuietly(c);
        }
    }
}
