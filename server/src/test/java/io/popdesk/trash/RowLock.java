package io.popdesk.trash;

import io.popdesk.support.DbInspector;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds one issue row {@code FOR UPDATE} on a connection of its own, the way {@code acceptance.md} 4절 makes a request
 * or a cleanup job of the app meet another at that row (S7A-AC-34, S7A-AC-42): every write of the app that locks or
 * updates the row waits, in the order it came, until {@link #release()}.
 *
 * <p>Hold it briefly: the cleanup jobs run one after another on one thread, so a job waiting here stops the others.
 * {@link #close()} releases too; use it in try-with-resources.
 */
public final class RowLock implements AutoCloseable {

    private final DbInspector db;
    private final Connection connection;
    private boolean held;

    /** Locks the issue row with ID {@code issueId}; fails when there is no such row. */
    public RowLock(DbInspector db, PostgreSQLContainer postgres, UUID issueId) {
        this.db = db;
        try {
            connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(), postgres.getPassword());
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("select id from \""
                    + db.schema().replace("\"", "\"\"") + "\".issue where id = ? for update")) {
                lock.setObject(1, issueId);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new AssertionError("No issue row " + issueId + " to lock");
                    }
                }
            }
            held = true;
        } catch (SQLException e) {
            throw new AssertionError("Could not lock the issue row " + issueId, e);
        }
    }

    /** How many statements of this database wait for a lock right now. */
    public long waitingForALock() {
        return db.count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                + "and pid <> pg_backend_pid() and datname = current_database()");
    }

    /** Waits until at least {@code n} statements wait for a lock: the requests sent so far reached the row. */
    public void awaitWaiting(long n, String what) {
        Wait.until(what + " waits for the held issue row", () -> waitingForALock() >= n ? Boolean.TRUE : null);
    }

    /** Ends the transaction that holds the row, so the waiting writes go on in their order. */
    public void release() {
        if (held) {
            try {
                connection.rollback();
                held = false;
            } catch (SQLException e) {
                throw new AssertionError("Could not release the issue row", e);
            }
        }
    }

    @Override
    public void close() {
        try {
            release();
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new AssertionError("Could not close the connection of a row lock", e);
            }
        }
    }
}
