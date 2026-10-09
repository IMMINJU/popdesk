package io.popdesk.notification.ops;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Raised operations alerts (table {@code ops_alert}), the staff inbox (table {@code notification}), and
 * the cleanup record (table {@code cleanup_run}).
 */
@Repository
public class OpsRepository {

    /** An inbox item. {@code kind} is an {@code OpsAlerts.Kind} name. */
    public record InboxRow(long id, String kind, Instant createdAt, Instant readAt) {}

    /** A cleanup job's row. */
    public record CleanupRow(String job, Instant lastRunAt, long deletedCount) {}

    private final JdbcClient jdbc;

    OpsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Alerts

    /** Raises the alert; false when it is raised already. */
    boolean insertAlert(String kind, Instant now) {
        return jdbc.sql("insert into ops_alert (kind, tenant_id, raised_at, last_notified_at) values (?, 1, ?, ?) "
                        + "on conflict (kind) do nothing")
                .params(kind, Timestamp.from(now), Timestamp.from(now)).update() == 1;
    }

    void deleteAlert(String kind) {
        jdbc.sql("delete from ops_alert where kind = ?").param(kind).update();
    }

    /** Raised alerts whose admins were last told at or before {@code before}, locked. */
    List<String> lockAlertsNotifiedBefore(Instant before) {
        return jdbc.sql("select kind from ops_alert where last_notified_at <= ? order by kind for update skip locked")
                .param(Timestamp.from(before)).query(String.class).list();
    }

    void updateNotified(String kind, Instant now) {
        jdbc.sql("update ops_alert set last_notified_at = ? where kind = ?").params(Timestamp.from(now), kind).update();
    }

    // Inbox

    void insertInbox(long accountId, String kind, Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, created_at) values (1, ?, ?, ?)")
                .params(accountId, kind, Timestamp.from(now)).update();
    }

    /** The account's items, newest first. */
    List<InboxRow> listInbox(long accountId, long offset, int limit) {
        return jdbc.sql("select id, kind, created_at, read_at from notification where account_id = ? "
                        + "order by id desc offset ? limit ?")
                .params(accountId, offset, limit).query(OpsRepository::inbox).list();
    }

    long countInbox(long accountId) {
        return jdbc.sql("select count(*) from notification where account_id = ?").param(accountId)
                .query(Long.class).single();
    }

    long countUnread(long accountId) {
        return jdbc.sql("select count(*) from notification where account_id = ? and read_at is null")
                .param(accountId).query(Long.class).single();
    }

    /** Marks the account's item read, keeping an earlier read time; false when the account has no such item. */
    boolean updateRead(long id, long accountId, Instant now) {
        return jdbc.sql("update notification set read_at = coalesce(read_at, ?) where id = ? and account_id = ?")
                .params(Timestamp.from(now), id, accountId).update() == 1;
    }

    // Cleanup record

    void upsertCleanup(String job, Instant ranAt, long deleted) {
        jdbc.sql("insert into cleanup_run (job, tenant_id, last_run_at, deleted_count) values (?, 1, ?, ?) "
                        + "on conflict (job) do update set last_run_at = excluded.last_run_at, "
                        + "deleted_count = excluded.deleted_count")
                .params(job, Timestamp.from(ranAt), deleted).update();
    }

    Optional<CleanupRow> findCleanup(String job) {
        return jdbc.sql("select job, last_run_at, deleted_count from cleanup_run where job = ?").param(job)
                .query(OpsRepository::cleanup).optional();
    }

    List<CleanupRow> listCleanups() {
        return jdbc.sql("select job, last_run_at, deleted_count from cleanup_run order by job")
                .query(OpsRepository::cleanup).list();
    }

    /** Size of the database, bytes. */
    long databaseSize() {
        return jdbc.sql("select pg_database_size(current_database())").query(Long.class).single();
    }

    private static InboxRow inbox(ResultSet rs, int n) throws SQLException {
        return new InboxRow(rs.getLong("id"), rs.getString("kind"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("read_at")));
    }

    private static CleanupRow cleanup(ResultSet rs, int n) throws SQLException {
        return new CleanupRow(rs.getString("job"), instant(rs.getTimestamp("last_run_at")),
                rs.getLong("deleted_count"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
