package io.popdesk.attachment;

import io.popdesk.support.DbInspector;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An advisory lock the test holds on a connection of its own, for {@link FailureTriggers} to wait
 * on: a {@code hold} trigger keeps the app's statement in the trigger, with its row lock, until the
 * test calls {@link #release()} (or at most 30 seconds). The test decides when the statement goes
 * on, instead of a fixed sleep.
 *
 * <p>The trigger polls with short {@code pg_sleep} calls, so a held statement shows in
 * {@code pg_stat_activity} with {@code wait_event = 'PgSleep'}. {@link #close()} releases the lock;
 * close it before the triggers, so no statement is left waiting when they are dropped.
 */
final class Hold implements AutoCloseable {

    private final Connection connection;
    private final long key = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
    private boolean held;

    Hold(DbInspector db, PostgreSQLContainer postgres) {
        try {
            connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(), postgres.getPassword());
            run("select pg_advisory_lock(?)");
            held = true;
        } catch (SQLException e) {
            throw new AssertionError("Could not take the advisory lock for a hold", e);
        }
    }

    /** The lock key, as the trigger gets it. */
    String key() {
        return Long.toString(key);
    }

    /** Waits until a statement of the app is held in a trigger, and returns its server process ID. */
    static int awaitHeld(DbInspector db, String what) {
        return Wait.until(what + " held in the trigger", () -> {
            var found = db.rows("select pid from pg_stat_activity where wait_event = 'PgSleep' "
                    + "and pid <> pg_backend_pid() and datname = current_database()");
            return found.size() == 1 ? ((Number) found.getFirst().get("pid")).intValue() : null;
        });
    }

    /** Lets the held statement go on. */
    void release() {
        if (held) {
            try {
                run("select pg_advisory_unlock(?)");
                held = false;
            } catch (SQLException e) {
                throw new AssertionError("Could not release the advisory lock of a hold", e);
            }
        }
    }

    private void run(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.executeQuery();
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new AssertionError("Could not close the connection of a hold", e);
        }
    }
}
