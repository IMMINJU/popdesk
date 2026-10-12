package io.popdesk.notification.ops;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Raised operations alerts (table {@code ops_alert}), the inbox of every account (table
 * {@code notification}), and the cleanup record (table {@code cleanup_run}).
 */
@Repository
public class OpsRepository {

    /**
     * An inbox item. {@code kind} is an {@code OpsAlerts.Kind} name, {@code MEMBERSHIP_REQUESTED}, or an
     * {@code IssueInbox.Kind} name. {@code companyCode} is the company a membership request is about,
     * null for the other kinds. {@code issueId}, {@code commentId}, and {@code actorId} are those of an
     * item about an issue; all null for the items of admins that are about none.
     */
    public record InboxRow(long id, String kind, String companyCode, UUID issueId, Long commentId, Long actorId,
            Instant createdAt, Instant readAt) {}

    /**
     * Which of an account's items a read is about: every kind or only {@code kinds}, and of the items
     * about an issue only those whose issue is in {@code issues} (S4-AC-42).
     *
     * @param kinds the kinds to read; null for every kind
     * @param issues the issues the account may see now
     */
    record Shown(long accountId, Collection<String> kinds, Collection<UUID> issues) {

        /** The condition on {@code notification}, with the parameters of {@link #params}. */
        static final String SQL = "account_id = ? and (cast(? as text[]) is null or kind = any(?)) "
                + "and (issue_id is null or issue_id = any(?))";

        Object[] params() {
            String[] names = kinds == null ? null : kinds.toArray(String[]::new);
            return new Object[] {accountId, names, names, issues.toArray(UUID[]::new)};
        }
    }

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
        insertInbox(accountId, kind, null, now);
    }

    void insertInbox(long accountId, String kind, String companyCode, Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, company_code, created_at) "
                        + "values (1, ?, ?, ?, ?)")
                .params(accountId, kind, companyCode, Timestamp.from(now)).update();
    }

    /** An item about an issue: the issue, the comment if it is about one, and who did it. */
    void insertInbox(long accountId, String kind, UUID issueId, Long commentId, long actorId, Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, issue_id, comment_id, actor_id, "
                        + "created_at) values (1, ?, ?, ?, ?, ?, ?)")
                .params(accountId, kind, issueId, commentId, actorId, Timestamp.from(now)).update();
    }

    /**
     * The issues the account's items of these kinds are about, each once; with {@code unreadOnly}, those
     * of its unread items only. Null {@code kinds} is every kind.
     */
    List<UUID> listInboxIssues(long accountId, Collection<String> kinds, boolean unreadOnly) {
        String[] names = kinds == null ? null : kinds.toArray(String[]::new);
        return jdbc.sql("select distinct issue_id from notification where account_id = ? and issue_id is not null "
                        + "and (cast(? as text[]) is null or kind = any(?))"
                        + (unreadOnly ? " and read_at is null" : ""))
                .params(accountId, names, names).query(UUID.class).list();
    }

    /**
     * One page of the items, newest first. The order names {@code account_id} too, though it is one
     * value here: with {@code order by id desc} alone the planner walked the primary key backward
     * through everybody's items; with both columns it takes the index on {@code (account_id, id)}.
     */
    List<InboxRow> listInbox(Shown shown, long offset, int limit) {
        return jdbc.sql("select id, kind, company_code, issue_id, comment_id, actor_id, created_at, read_at "
                        + "from notification where " + Shown.SQL
                        + " order by account_id desc, id desc offset ? limit ?")
                .params(with(shown.params(), offset, limit)).query(OpsRepository::inbox).list();
    }

    long countInbox(Shown shown) {
        return jdbc.sql("select count(*) from notification where " + Shown.SQL).params(shown.params())
                .query(Long.class).single();
    }

    long countUnread(Shown shown) {
        return jdbc.sql("select count(*) from notification where " + Shown.SQL + " and read_at is null")
                .params(shown.params()).query(Long.class).single();
    }

    /** Marks the account's item read, keeping an earlier read time; false when the account has no such item. */
    boolean updateRead(long id, long accountId, Instant now) {
        return jdbc.sql("update notification set read_at = coalesce(read_at, ?) where id = ? and account_id = ?")
                .params(Timestamp.from(now), id, accountId).update() == 1;
    }

    /** Marks every unread item of the account read, whatever it is about. */
    void updateAllRead(long accountId, Instant now) {
        jdbc.sql("update notification set read_at = ? where account_id = ? and read_at is null")
                .params(Timestamp.from(now), accountId).update();
    }

    /** Deletes every item about the comment. */
    void deleteInboxOfComment(long commentId) {
        jdbc.sql("delete from notification where comment_id = ?").param(commentId).update();
    }

    /** Deletes the account's unread items of this kind about the comment. */
    void deleteUnreadInbox(long commentId, long accountId, String kind) {
        jdbc.sql("delete from notification where comment_id = ? and account_id = ? and kind = ? "
                        + "and read_at is null")
                .params(commentId, accountId, kind).update();
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
        return new InboxRow(rs.getLong("id"), rs.getString("kind"), rs.getString("company_code"),
                rs.getObject("issue_id", UUID.class), rs.getObject("comment_id", Long.class),
                rs.getObject("actor_id", Long.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("read_at")));
    }

    private static Object[] with(Object[] first, Object... more) {
        Object[] out = new Object[first.length + more.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(more, 0, out, first.length, more.length);
        return out;
    }

    private static CleanupRow cleanup(ResultSet rs, int n) throws SQLException {
        return new CleanupRow(rs.getString("job"), instant(rs.getTimestamp("last_run_at")),
                rs.getLong("deleted_count"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
