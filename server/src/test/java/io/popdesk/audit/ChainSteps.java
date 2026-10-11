package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.export;
import static io.popdesk.audit.AuditSteps.jsonLines;
import static io.popdesk.audit.AuditSteps.number;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the hash chain tests of slice 1b that {@link AuditSteps} does not have: the integrity
 * check, the retention job, and changes to audit rows made in the database behind the app's back.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
final class ChainSteps {

    static final String VERIFY = AuditSteps.LOG + "/verify";

    /** The cleanup job that deletes entries older than {@code POPDESK_AUDIT_RETENTION} (design section 1). */
    static final String RETENTION_JOB = "audit-retention";

    private ChainSteps() {}

    /** {@code POST /api/console/audit-log/verify}. Sent, any status. */
    static ApiResponse verify(Session admin) {
        return admin.client().post(VERIFY).send();
    }

    /** Runs the integrity check and expects "all entries match": 200, valid, a count, no reason, no entry. */
    static JsonNode assertValid(Session admin) {
        JsonNode answer = verify(admin).expectStatus(200).json();
        assertThat(answer.path("valid").isBoolean() && answer.path("valid").booleanValue())
                .as("valid in the integrity check's answer %s", answer).isTrue();
        assertThat(number(answer, "checkedCount")).as("checkedCount in %s", answer).isPositive();
        assertThat(answer.path("reason").isNull()).as("reason is null in %s", answer).isTrue();
        assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
        return answer;
    }

    /** Runs the integrity check and expects 200 with {@code valid: false} and this reason. */
    static JsonNode assertBroken(Session admin, String reason) {
        JsonNode answer = verify(admin).expectStatus(200).json();
        assertThat(answer.path("valid").isBoolean() && !answer.path("valid").booleanValue())
                .as("valid is false in the integrity check's answer %s", answer).isTrue();
        assertThat(answer.path("reason").asString(null)).as("reason in %s", answer).isEqualTo(reason);
        return answer;
    }

    /** The whole chain as it is now: the lines of a JSON Lines export without a filter, in chain order. */
    static List<JsonNode> chain(Session admin) {
        return jsonLines(export(admin, "JSONL").expectStatus(200));
    }

    /**
     * Waits until the retention job has run at or after {@code due} (app time) and returns its one
     * row of the cleanup record: {@code last_run_at} and {@code deleted_count} (the table of V3).
     */
    static Map<String, Object> awaitRetentionRun(DbInspector db, Instant due) {
        return Wait.until("the " + RETENTION_JOB + " job run at or after " + due, Duration.ofSeconds(60), () -> {
            List<Map<String, Object>> rows = db.rows("select last_run_at, deleted_count from cleanup_run where job = ?",
                    RETENTION_JOB);
            if (rows.size() != 1) {
                return null;
            }
            return lastRun(rows.getFirst()).isBefore(due) ? null : rows.getFirst();
        });
    }

    /** {@code last_run_at} of a cleanup record row. */
    static Instant lastRun(Map<String, Object> cleanupRow) {
        Object at = cleanupRow.get("last_run_at");
        if (at instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (at instanceof OffsetDateTime time) {
            return time.toInstant();
        }
        throw new AssertionError("last_run_at of the cleanup record is not a time: " + at);
    }

    /**
     * What someone with access to the database could do to the audit table, on a connection of the
     * test's own: change a value of a row, delete a row, keep readers out. Uses only the columns the
     * table had before this slice ({@code id}, {@code action}, {@code actor}, {@code target},
     * {@code changes}, {@code occurred_at}); a row is kept and put back whole, as JSON, so no other
     * column is named here.
     *
     * <p>Tests that use this run in a context of their own and put back what they changed.
     */
    static final class Tampering {

        private final DbInspector db;
        private final PostgreSQLContainer postgres;

        Tampering(DbInspector db, PostgreSQLContainer postgres) {
            this.db = db;
            this.postgres = postgres;
        }

        /** The row of an entry, whole, as JSON text. */
        String row(long id) {
            try (Connection connection = connect();
                    PreparedStatement statement = connection.prepareStatement(
                            "select row_to_json(t)::text from audit_log t where id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("No audit row with id " + id);
                    }
                    return rows.getString(1);
                }
            } catch (SQLException e) {
                throw new AssertionError("Could not read the audit row " + id, e);
            }
        }

        /** {@code update audit_log set <assignment> where id = ?}; exactly one row must change. */
        void change(long id, String assignment) {
            run("update audit_log set " + assignment + " where id = ?", id);
        }

        /** Deletes the row of an entry. */
        void delete(long id) {
            run("delete from audit_log where id = ?", id);
        }

        /** Puts a row back as {@link #row} read it, whether it was changed or deleted since. */
        void putBack(String row) {
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try (PreparedStatement delete = connection.prepareStatement(
                        "delete from audit_log where id = (cast(? as jsonb) ->> 'id')::bigint");
                        PreparedStatement insert = connection.prepareStatement("insert into audit_log "
                                + "select * from jsonb_populate_record(null::audit_log, cast(? as jsonb))")) {
                    delete.setString(1, row);
                    delete.executeUpdate();
                    insert.setString(1, row);
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                throw new AssertionError("Could not put the audit row back: " + row, e);
            }
        }

        /**
         * Keeps every reader and writer out of the audit table until the returned connection is let
         * go ({@link #letGo}). The app's statements give up on a lock after 5 seconds, so let go
         * well before.
         */
        Connection lockAgainstReaders() {
            try {
                Connection connection = connect();
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("lock table audit_log in access exclusive mode");
                }
                return connection;
            } catch (SQLException e) {
                throw new AssertionError("Could not lock the audit table", e);
            }
        }

        static void letGo(Connection lock) {
            try {
                lock.rollback();
                lock.close();
            } catch (SQLException e) {
                throw new AssertionError("Could not release the lock on the audit table", e);
            }
        }

        /** Whether a statement of the app that names an audit table waits for a lock right now. */
        boolean auditStatementWaitsForALock() {
            return db.count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                    + "and pid <> pg_backend_pid() and datname = current_database() "
                    + "and position('audit' in lower(query)) > 0") > 0;
        }

        private void run(String sql, long id) {
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                int changed = statement.executeUpdate();
                if (changed != 1) {
                    throw new AssertionError(sql + " with id " + id + " changed " + changed + " rows, not 1");
                }
            } catch (SQLException e) {
                throw new AssertionError("Could not run on the audit table: " + sql, e);
            }
        }

        private Connection connect() throws SQLException {
            Connection connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            connection.setSchema(db.schema());
            return connection;
        }
    }
}
