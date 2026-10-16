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
     * item about an issue; all null for the items of admins that are about none. {@code linkedIssueId} is the
     * linked issue that changed, of the items {@code LINKED_ISSUE_*} only (S6C-AC-31). {@code slaSummary} holds the
     * counts of a {@code SLA_RECALCULATED} item, null for the other kinds (S8B-AC-15).
     */
    public record InboxRow(long id, String kind, String companyCode, UUID issueId, Long commentId, Long actorId,
            Instant createdAt, Instant readAt, Long taskId, UUID linkedIssueId, SlaCounts slaSummary) {}

    /** The three counts of a {@code SLA_RECALCULATED} item (S8B-AC-14). */
    public record SlaCounts(int becameBreached, int becameWarning, int released) {}

    /**
     * Which of an account's items a read is about: every kind or only {@code kinds}, and of the items
     * about an issue only those whose issue is in {@code issues} (S4-AC-42), and whose linked issue is
     * too when they have one (S6C-AC-33).
     *
     * @param kinds the kinds to read; null for every kind
     * @param issues the issues the account may see now
     */
    record Shown(long accountId, Collection<String> kinds, Collection<UUID> issues) {

        /**
         * The condition on {@code notification}, with the parameters of {@link #params}. The account is
         * written as a range of one value on purpose; see {@link OpsRepository#listInbox}.
         */
        static final String SQL = "account_id >= ? and account_id <= ? "
                + "and (cast(? as text[]) is null or kind = any(?)) and (issue_id is null or issue_id = any(?)) "
                + "and (linked_issue_id is null or linked_issue_id = any(?))";

        Object[] params() {
            String[] names = kinds == null ? null : kinds.toArray(String[]::new);
            UUID[] seen = issues.toArray(UUID[]::new);
            return new Object[] {accountId, accountId, names, names, seen, seen};
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

    /** A {@code SLA_RECALCULATED} item: about no issue, with the counts of the summary (S8B-AC-14). */
    void insertInboxSlaSummary(long accountId, String kind, int becameBreached, int becameWarning, int released,
            Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, sla_became_breached, sla_became_warning, "
                        + "sla_released, created_at) values (1, ?, ?, ?, ?, ?, ?)")
                .params(accountId, kind, becameBreached, becameWarning, released, Timestamp.from(now)).update();
    }

    /** An item about an issue: the issue, the comment if it is about one, and who did it. */
    void insertInbox(long accountId, String kind, UUID issueId, Long commentId, long actorId, Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, issue_id, comment_id, actor_id, "
                        + "created_at) values (1, ?, ?, ?, ?, ?, ?)")
                .params(accountId, kind, issueId, commentId, actorId, Timestamp.from(now)).update();
    }

    /**
     * The issues the account's items of these kinds are about, and their linked issues, each once; with
     * {@code unreadOnly}, those of its unread items only. Null {@code kinds} is every kind.
     */
    List<UUID> listInboxIssues(long accountId, Collection<String> kinds, boolean unreadOnly) {
        String[] names = kinds == null ? null : kinds.toArray(String[]::new);
        String items = "from notification where account_id = ? and (cast(? as text[]) is null or kind = any(?))"
                + (unreadOnly ? " and read_at is null" : "");
        return jdbc.sql("select issue_id " + items + " and issue_id is not null "
                        + "union select linked_issue_id " + items + " and linked_issue_id is not null")
                .params(accountId, names, names, accountId, names, names).query(UUID.class).list();
    }

    /**
     * One page of the items, newest first, read from the index on {@code (account_id, id)}.
     *
     * <p>With {@code account_id = ? order by id desc limit ?} the planner walks the primary key backward
     * through everybody's items until it has a page of this account's, which is slow for an account
     * whose items are old. Adding {@code account_id} to the order does not help while the condition is
     * an equality: the planner drops a column that has one value from the sort. So the account is a
     * range of one value and the order names both columns; then only the index on
     * {@code (account_id, id)} gives the order, read backward, or the account's rows are sorted.
     */
    List<InboxRow> listInbox(Shown shown, long offset, int limit) {
        return jdbc.sql("select id, kind, company_code, issue_id, comment_id, actor_id, created_at, read_at, task_id, "
                        + "linked_issue_id, sla_became_breached, sla_became_warning, sla_released from notification "
                        + "where " + Shown.SQL
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

    /** An item about a task of an issue: the issue, the task, and who did it (S6B-AC-33). */
    void insertInboxAboutTask(long accountId, String kind, UUID issueId, long taskId, long actorId, Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, issue_id, task_id, actor_id, created_at) "
                        + "values (1, ?, ?, ?, ?, ?, ?)")
                .params(accountId, kind, issueId, taskId, actorId, Timestamp.from(now)).update();
    }

    /** Deletes every item about the task (S6B-AC-36). */
    void deleteInboxOfTask(long taskId) {
        jdbc.sql("delete from notification where task_id = ?").param(taskId).update();
    }

    void deleteInboxOfIssue(UUID issueId) {
        jdbc.sql("delete from notification where issue_id = ? or linked_issue_id = ?").params(issueId, issueId)
                .update();
    }

    /**
     * An item about the account's issue and a linked issue that changed (S6C-AC-31), unless the account has
     * an unread item of the same kind about the same two issues (S6C-AC-35).
     */
    void insertInboxAboutLinkedIssue(long accountId, String kind, UUID issueId, UUID linkedIssueId, long actorId,
            Instant now) {
        jdbc.sql("insert into notification (tenant_id, account_id, kind, issue_id, linked_issue_id, actor_id, "
                        + "created_at) select 1, ?, ?, ?, ?, ?, ? where not exists (select 1 from notification "
                        + "where account_id = ? and kind = ? and issue_id = ? and linked_issue_id = ? "
                        + "and read_at is null)")
                .params(accountId, kind, issueId, linkedIssueId, actorId, Timestamp.from(now), accountId, kind,
                        issueId, linkedIssueId)
                .update();
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
        Integer breached = rs.getObject("sla_became_breached", Integer.class);
        return new InboxRow(rs.getLong("id"), rs.getString("kind"), rs.getString("company_code"),
                rs.getObject("issue_id", UUID.class), rs.getObject("comment_id", Long.class),
                rs.getObject("actor_id", Long.class), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("read_at")), rs.getObject("task_id", Long.class),
                rs.getObject("linked_issue_id", UUID.class), breached == null ? null
                        : new SlaCounts(breached, rs.getInt("sla_became_warning"), rs.getInt("sla_released")));
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
