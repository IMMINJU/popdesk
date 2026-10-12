package io.popdesk.helpdesk.issue;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Issues (table {@code issue}). Every read takes the {@link Viewer} and applies {@link IssueScope} in
 * the query (S0-AC-84); an issue outside the scope is the same as one that does not exist (COM-1).
 */
@Repository
public class IssueRepository {

    /** An account an issue names besides its reporter; {@code photoUpdatedAt} is null without a profile photo. */
    public record Staff(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /**
     * One issue with its company and reporter; {@code reporterPhotoUpdatedAt} is null without a profile photo.
     * {@code createdBy} is the staff member who filed it for the reporter, null when the reporter filed it
     * (ISSUE-9). {@code assignee} is null when nobody is assigned; {@code assigneeUnavailable} says that
     * the assignee can no longer see the issue ({@link #ASSIGNEE_UNAVAILABLE}).
     */
    public record IssueRow(UUID id, long companyId, String companyCode, String companyName, int number, String title,
            String content, IssueStatus status, Priority priority, boolean privateIssue, long reporterId,
            String reporterName, Role reporterRole, Instant reporterPhotoUpdatedAt, Staff createdBy, String channel,
            Staff assignee, boolean assigneeUnavailable, Instant firstResponseAt,
            Instant resolvedAt, Instant closedAt, long version, Instant createdAt, Instant updatedAt) {

        public String key() {
            return companyCode + "-" + number;
        }

        /**
         * Whether the issue can be reopened at {@code now} (STATUS-4): it is {@code RESOLVED}, or it was
         * closed less than {@link #REOPEN_WINDOW} ago. It says nothing about who asks.
         */
        public boolean reopenable(Instant now) {
            return status == IssueStatus.RESOLVED
                    || status == IssueStatus.CLOSED && closedAt != null && closedAt.isAfter(now.minus(REOPEN_WINDOW));
        }
    }

    /** How long after it was closed an issue can still be reopened (STATUS-4). */
    public static final Duration REOPEN_WINDOW = Duration.ofDays(14);

    /**
     * "The assignee can no longer see the issue" (ISSUE-11), as SQL on the issue alias {@code i}, without
     * parameters: the issue has an assignee whose account is not {@code ACTIVE}, or who is an agent
     * without an assignment to the issue's company; an assignment to another company does not count. An
     * admin sees every issue. The list, the detail, and the list filter use this one condition.
     */
    public static final String ASSIGNEE_UNAVAILABLE = "exists (select 1 from account ua where ua.id = i.assignee_id "
            + "and (ua.status <> 'ACTIVE' or (ua.role = 'AGENT' and not exists (select 1 from company_agent uca "
            + "where uca.company_id = i.company_id and uca.account_id = ua.id))))";

    /** Open issues an account is the assignee of (ACCT-7), as SQL on {@code i} with one parameter, the account. */
    private static final String OPEN_ASSIGNMENT = "i.assignee_id = ? and i.status <> 'CLOSED'";

    private static final String SELECT = "select i.id, i.company_id, c.code, c.name, i.number, i.title, i.content, "
            + "i.status, i.priority, i.is_private, i.reporter_id, r.name as reporter_name, r.role as reporter_role, "
            + "rp.updated_at as reporter_photo_updated_at, i.channel, i.first_response_at, i.resolved_at, i.closed_at, "
            + "i.version, i.created_at, i.updated_at, "
            + "i.created_by, cb.name as created_by_name, cb.role as created_by_role, "
            + "cbp.updated_at as created_by_photo_updated_at, "
            + "i.assignee_id, asg.name as assignee_name, asg.role as assignee_role, "
            + "asgp.updated_at as assignee_photo_updated_at, " + ASSIGNEE_UNAVAILABLE + " as assignee_unavailable "
            + "from issue i join company c on c.id = i.company_id join account r on r.id = i.reporter_id "
            + "left join account_photo rp on rp.account_id = i.reporter_id "
            + "left join account cb on cb.id = i.created_by "
            + "left join account_photo cbp on cbp.account_id = i.created_by "
            + "left join account asg on asg.id = i.assignee_id "
            + "left join account_photo asgp on asgp.account_id = i.assignee_id ";

    private final JdbcClient jdbc;

    IssueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The issue with this key, if the viewer may see it. */
    public Optional<IssueRow> findByKey(Viewer viewer, IssueKey key) {
        return jdbc.sql(SELECT + "where c.code = ? and i.number = ? and " + IssueScope.SQL)
                .params(concat(new Object[] {key.companyCode(), key.number()}, IssueScope.params(viewer)))
                .query(IssueRepository::issue).optional();
    }

    /**
     * The issue with this key, if the viewer may see it, locked {@code FOR NO KEY UPDATE} until the end
     * of the transaction. A write under an issue calls this before any INSERT (architecture.md
     * "Concurrency and lock order", step 1).
     */
    public Optional<IssueRow> lockByKey(Viewer viewer, IssueKey key) {
        return jdbc.sql(SELECT + "where c.code = ? and i.number = ? and " + IssueScope.SQL + " for no key update of i")
                .params(concat(new Object[] {key.companyCode(), key.number()}, IssueScope.params(viewer)))
                .query(IssueRepository::issue).optional();
    }

    /** Whether the viewer may see the issue with this ID. */
    public boolean visible(Viewer viewer, UUID id) {
        return jdbc.sql("select count(*) from issue i where i.id = ? and " + IssueScope.SQL)
                .params(concat(new Object[] {id}, IssueScope.params(viewer)))
                .query(Long.class).single() > 0;
    }

    /** One page of the issues the viewer may see, newest first. */
    public List<IssueRow> list(Viewer viewer, long offset, int limit) {
        return jdbc.sql(SELECT + "where " + IssueScope.SQL
                        + " order by i.created_at desc, i.number desc, i.id offset ? limit ?")
                .params(concat(IssueScope.params(viewer), offset, limit))
                .query(IssueRepository::issue).list();
    }

    public long count(Viewer viewer) {
        return jdbc.sql("select count(*) from issue i where " + IssueScope.SQL)
                .params(IssueScope.params(viewer)).query(Long.class).single();
    }

    /**
     * How many issues that are not {@code CLOSED} have the account as their assignee, among those the
     * viewer may see; with {@code companyId}, in that company only (S3-AC-68). It counts what
     * {@link #updateAssigneesCleared} clears for the same account and company.
     */
    public long countOpenAssignments(Viewer viewer, long accountId, Long companyId) {
        Object[] params = concat(IssueScope.params(viewer), accountId);
        return jdbc.sql("select count(*) from issue i where " + IssueScope.SQL + " and " + OPEN_ASSIGNMENT
                        + (companyId == null ? "" : " and i.company_id = ?"))
                .params(companyId == null ? params : concat(params, companyId))
                .query(Long.class).single();
    }

    // Writes

    /**
     * A new issue with the number the company counter gave it (ISSUE-3), and the text the search looks in.
     * {@code createdBy} is the staff member who files it for the reporter, or null (ISSUE-9).
     */
    void insert(UUID id, long companyId, int number, String title, String content, Priority priority,
            boolean privateIssue, long reporterId, Long createdBy, String channel, Instant now) {
        jdbc.sql("insert into issue (id, tenant_id, company_id, number, title, content, status, priority, is_private, "
                        + "reporter_id, created_by, channel, version, created_at, updated_at, search_text) "
                        + "values (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)")
                .params(id, companyId, number, title, content, IssueStatus.RECEIVED.name(), priority.name(),
                        privateIssue, reporterId, createdBy, channel, Timestamp.from(now), Timestamp.from(now),
                        SearchText.of(title, content))
                .update();
    }

    /**
     * Sets the assignee, or clears it with null. The caller has locked the issue row and seen that the
     * assignee changes. A change to the row raises its version (S3-AC-24).
     */
    void updateAssignee(UUID id, Long assigneeId, Instant now) {
        jdbc.sql("update issue set assignee_id = ?, updated_at = ?, version = version + 1 where id = ?")
                .params(assigneeId, Timestamp.from(now), id)
                .update();
    }

    /**
     * Clears the assignee of every issue that is not {@code CLOSED} and has this account as its assignee;
     * with {@code companyId}, in that company only (ACCT-7, S3-AC-69). One conditional UPDATE, without
     * locking the rows one by one first (STATUS-7): an issue that got another assignee or was closed
     * meanwhile no longer matches and stays as it is. Each cleared issue gets a new version.
     *
     * @return the issues that were cleared
     */
    public List<UUID> updateAssigneesCleared(long accountId, Long companyId, Instant now) {
        List<Object> params = new ArrayList<>(List.of(Timestamp.from(now), accountId));
        if (companyId != null) {
            params.add(companyId);
        }
        return jdbc.sql("update issue i set assignee_id = null, updated_at = ?, version = version + 1 where "
                        + OPEN_ASSIGNMENT + (companyId == null ? "" : " and i.company_id = ?") + " returning i.id")
                .params(params)
                .query(UUID.class).list();
    }

    /**
     * Sets the first response time once (S0-AC-46): only while it is empty, so a later staff comment
     * changes nothing. A change to the row raises its version (S0-AC-45).
     */
    public void updateFirstResponse(UUID id, Instant at) {
        jdbc.sql("update issue set first_response_at = ?, updated_at = ?, version = version + 1 "
                        + "where id = ? and first_response_at is null")
                .params(Timestamp.from(at), Timestamp.from(at), id)
                .update();
    }

    /**
     * Moves the issue from one status to another, if it still has the first one (STATUS-7): a change
     * that came in between leaves this one without a row to change, and the caller reads the issue
     * again. {@code RESOLVED} sets the resolved time and {@code CLOSED} the closed time; {@code CLOSED}
     * keeps the resolved time, every other status clears both (STATUS-6, STATUS-4).
     *
     * @param closedAfter for a reopening: a {@code CLOSED} issue only changes when it was closed after
     *     this time; null for every other change
     * @return whether the issue changed
     */
    boolean updateStatus(UUID id, IssueStatus from, IssueStatus to, Instant now, Instant closedAfter) {
        Timestamp at = Timestamp.from(now);
        List<Object> params = new ArrayList<>(List.of(to.name()));
        String times = switch (to) {
            case RESOLVED -> {
                params.add(at);
                yield "resolved_at = ?, closed_at = null";
            }
            case CLOSED -> {
                params.add(at);
                yield "closed_at = ?";
            }
            default -> "resolved_at = null, closed_at = null";
        };
        params.addAll(List.of(at, id, from.name()));
        String stillOpenable = "";
        if (closedAfter != null) {
            stillOpenable = " and (status <> 'CLOSED' or closed_at > ?)";
            params.add(Timestamp.from(closedAfter));
        }
        return jdbc.sql("update issue set status = ?, " + times + ", updated_at = ?, version = version + 1 "
                        + "where id = ? and status = ?" + stillOpenable)
                .params(params)
                .update() == 1;
    }

    /**
     * Stores the title, the content, and the priority of an edit, and the text the search looks in
     * (S3-AC-16). The caller has locked the issue row and checked its version.
     */
    void updateFields(UUID id, String title, String content, Priority priority, Instant now) {
        jdbc.sql("update issue set title = ?, content = ?, priority = ?, search_text = ?, updated_at = ?, "
                        + "version = version + 1 where id = ?")
                .params(title, content, priority.name(), SearchText.of(title, content), Timestamp.from(now), id)
                .update();
    }

    private static IssueRow issue(ResultSet rs, int row) throws SQLException {
        Timestamp firstResponse = rs.getTimestamp("first_response_at");
        Timestamp resolved = rs.getTimestamp("resolved_at");
        Timestamp closed = rs.getTimestamp("closed_at");
        Timestamp reporterPhoto = rs.getTimestamp("reporter_photo_updated_at");
        return new IssueRow(rs.getObject("id", UUID.class), rs.getLong("company_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("number"), rs.getString("title"), rs.getString("content"),
                IssueStatus.valueOf(rs.getString("status")), Priority.valueOf(rs.getString("priority")),
                rs.getBoolean("is_private"), rs.getLong("reporter_id"), rs.getString("reporter_name"),
                Role.valueOf(rs.getString("reporter_role")), reporterPhoto == null ? null : reporterPhoto.toInstant(),
                staff(rs, "created_by", "created_by"), rs.getString("channel"),
                staff(rs, "assignee_id", "assignee"), rs.getBoolean("assignee_unavailable"),
                firstResponse == null ? null : firstResponse.toInstant(),
                resolved == null ? null : resolved.toInstant(), closed == null ? null : closed.toInstant(),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /** The account in column {@code id}, with its name, role, and photo time under {@code prefix}; null when empty. */
    private static Staff staff(ResultSet rs, String id, String prefix) throws SQLException {
        long accountId = rs.getLong(id);
        if (rs.wasNull()) {
            return null;
        }
        Timestamp photo = rs.getTimestamp(prefix + "_photo_updated_at");
        return new Staff(accountId, rs.getString(prefix + "_name"), Role.valueOf(rs.getString(prefix + "_role")),
                photo == null ? null : photo.toInstant());
    }
}
