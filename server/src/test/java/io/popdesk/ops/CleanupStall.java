package io.popdesk.ops;

import io.popdesk.support.AppLog;
import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Wait;
import io.popdesk.trash.TrashJobs;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Makes cleanup jobs (OPS-2) fail on purpose, for the alert of slice 7b ({@code CLEANUP_STALLED}, OPS-1, S7B-AC-10).
 *
 * <p>The app reviews its alerts in the housekeeping round, on the thread of the jobs, after the jobs of the round, and
 * only while it holds the instance lock. Moving the clock alone runs the jobs and they succeed, so a test makes a job
 * fail instead: a connection of its own locks the job's table, and the app's statement gives up after its
 * {@code lock_timeout} of 5 seconds. A job that failed is tried again a minute of app time later, so while the clock
 * stands still after a failure the job does not run again, the lock can be let go, and the job stays late.
 *
 * <p>Each job here is the only one that reads its table and is late at the times the tests use: {@code audit_log} is
 * read by {@code audit-retention} alone (a daily job); {@code attachment} by {@code temporary-image-cleanup} (every 10
 * minutes) and the daily {@code attachment-cleanup}, which is late only 48 hours after its last success.
 *
 * <p>Public, so that other packages (the hidden E2E checks among them) can raise the alert.
 */
public final class CleanupStall {

    /** The kind of the inbox item. */
    public static final String KIND = "CLEANUP_STALLED";

    /** What a job may be late, on top of its interval, before the alert (acceptance.md 5절 3). */
    public static final Duration GRACE = Duration.ofDays(1);

    /** A cleanup job, a table it reads, and how often it runs. */
    public record Job(String name, String table, Duration interval) {

        /** How long after its last success the job counts as stalled. */
        public Duration stalledAfter() {
            return interval.plus(GRACE);
        }
    }

    public static final Job DAILY = new Job("audit-retention", "audit_log", Duration.ofDays(1));
    public static final Job TEN_MINUTES = new Job("temporary-image-cleanup", "attachment", Duration.ofMinutes(10));

    /** Every cleanup job of the app (docs/operate/configuration.md), each at most a day apart. */
    public static final List<String> JOBS = List.of("outbox-failed-cleanup", "attachment-cleanup", "session-cleanup",
            "password-reset-cleanup", "audit-retention", "idempotency-key-cleanup", "temporary-image-cleanup",
            "trash-purge", "issue-auto-close");

    /** A failed run waits for locks one after another (5 seconds each); allow several. */
    private static final Duration FAILURE = Duration.ofSeconds(60);

    private final Fixtures fixtures;
    private final DbInspector db;
    private final PostgreSQLContainer postgres;
    private final TestClock clock;
    private final AppLog log;

    public CleanupStall(Fixtures fixtures, DbInspector db, PostgreSQLContainer postgres, TestClock clock, AppLog log) {
        this.fixtures = fixtures;
        this.db = db;
        this.postgres = postgres;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Moves the clock two days on and waits until every job ran at that time, so none is late and an alert raised
     * before is resolved. Gives that time.
     */
    public Instant everyJobRan() {
        Instant now = clock.advance(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS);
        Session admin = fixtures.signInAdmin();
        for (String job : JOBS) {
            TrashJobs.awaitRun(admin, job, now);
        }
        return now;
    }

    /** The last success of the job, from the system status. */
    public Instant lastRun(Job job) {
        Session admin = fixtures.signInAdmin();
        return TrashJobs.lastRunAt(Wait.until("a run of " + job.name(), TrashJobs.RUN,
                () -> TrashJobs.run(admin, job.name(), Instant.EPOCH)));
    }

    /**
     * With the tables of {@code jobs} locked, moves the clock to {@code at} and waits until the run of each of them
     * there failed; then lets the tables go. The other jobs run at {@code at} as usual.
     */
    public void failAt(Instant at, Job... jobs) {
        Duration ahead = Duration.between(clock.instant(), at);
        if (ahead.isNegative() || ahead.isZero()) {
            throw new AssertionError("The clock is already at " + clock.instant() + ", not before " + at);
        }
        Connection lock = lock(Arrays.stream(jobs).map(Job::table).distinct().toList());
        try {
            AppLog.Mark mark = log.mark();
            clock.advance(ahead);
            for (Job job : jobs) {
                Wait.until("a failed run of " + job.name() + " at " + at + " while " + job.table() + " is locked",
                        FAILURE, () -> log.lines(mark).stream().filter(line -> failureOf(line, job)).findFirst()
                                .orElse(null));
            }
        } finally {
            letGo(lock);
        }
    }

    /** A log line of the app that names the job and its failure. */
    private static boolean failureOf(String line, Job job) {
        return line.contains(job.name()) && line.toLowerCase(Locale.ROOT).contains("fail");
    }

    private Connection lock(List<String> tables) {
        try {
            Connection connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            connection.setSchema(db.schema());
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("lock table " + tables.stream().map(t -> "\"" + t + "\"")
                        .collect(Collectors.joining(", ")) + " in access exclusive mode");
            }
            return connection;
        } catch (SQLException e) {
            throw new AssertionError("Could not lock " + tables, e);
        }
    }

    private static void letGo(Connection lock) {
        try {
            lock.rollback();
            lock.close();
        } catch (SQLException e) {
            throw new AssertionError("Could not let the locked tables go", e);
        }
    }
}
