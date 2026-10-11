package io.popdesk.helpdesk.issue;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

    /** One issue with its company and reporter; {@code reporterPhotoUpdatedAt} is null without a profile photo. */
    public record IssueRow(UUID id, long companyId, String companyCode, String companyName, int number, String title,
            String content, IssueStatus status, Priority priority, boolean privateIssue, long reporterId,
            String reporterName, Role reporterRole, Instant reporterPhotoUpdatedAt, String channel, Instant firstResponseAt, long version,
            Instant createdAt, Instant updatedAt) {

        public String key() {
            return companyCode + "-" + number;
        }
    }

    private static final String SELECT = "select i.id, i.company_id, c.code, c.name, i.number, i.title, i.content, "
            + "i.status, i.priority, i.is_private, i.reporter_id, r.name as reporter_name, r.role as reporter_role, "
            + "rp.updated_at as reporter_photo_updated_at, i.channel, i.first_response_at, i.version, i.created_at, i.updated_at "
            + "from issue i join company c on c.id = i.company_id join account r on r.id = i.reporter_id "
            + "left join account_photo rp on rp.account_id = i.reporter_id ";

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

    // Writes

    /** A new issue with the number the company counter gave it (ISSUE-3). */
    void insert(UUID id, long companyId, int number, String title, String content, Priority priority,
            boolean privateIssue, long reporterId, String channel, Instant now) {
        jdbc.sql("insert into issue (id, tenant_id, company_id, number, title, content, status, priority, is_private, "
                        + "reporter_id, channel, version, created_at, updated_at) "
                        + "values (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)")
                .params(id, companyId, number, title, content, IssueStatus.RECEIVED.name(), priority.name(),
                        privateIssue, reporterId, channel, Timestamp.from(now), Timestamp.from(now))
                .update();
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

    private static IssueRow issue(ResultSet rs, int row) throws SQLException {
        Timestamp firstResponse = rs.getTimestamp("first_response_at");
        Timestamp reporterPhoto = rs.getTimestamp("reporter_photo_updated_at");
        return new IssueRow(rs.getObject("id", UUID.class), rs.getLong("company_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("number"), rs.getString("title"), rs.getString("content"),
                IssueStatus.valueOf(rs.getString("status")), Priority.valueOf(rs.getString("priority")),
                rs.getBoolean("is_private"), rs.getLong("reporter_id"), rs.getString("reporter_name"),
                Role.valueOf(rs.getString("reporter_role")), reporterPhoto == null ? null : reporterPhoto.toInstant(),
                rs.getString("channel"),
                firstResponse == null ? null : firstResponse.toInstant(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
