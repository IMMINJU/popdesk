package io.popdesk.helpdesk.comment;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueScope;
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
 * Comments (table {@code comment}). Every read takes the {@link Viewer}: the issue must be one the
 * viewer may see ({@link IssueScope}), and for customers internal comments do not exist (S0-AC-53).
 */
@Repository
public class CommentRepository {

    /** One comment with its author. */
    public record CommentRow(long id, UUID issueId, long authorId, String authorName, Role authorRole,
            boolean internal, String content, Instant createdAt) {}

    private static final String SELECT = "select cm.id, cm.issue_id, cm.author_id, a.name, a.role, cm.internal, "
            + "cm.content, cm.created_at from comment cm join issue i on i.id = cm.issue_id "
            + "join account a on a.id = cm.author_id ";

    /** Internal comments only for staff; one parameter, the viewer's role. */
    private static final String VISIBLE = " and (not cm.internal or ? <> 'CUSTOMER')";

    private final JdbcClient jdbc;

    CommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One comment of the issue, if the viewer may see it. */
    public Optional<CommentRow> find(Viewer viewer, UUID issueId, long id) {
        return jdbc.sql(SELECT + "where cm.id = ? and cm.issue_id = ? and " + IssueScope.SQL + VISIBLE)
                .params(concat(concat(new Object[] {id, issueId}, IssueScope.params(viewer)), viewer.role().name()))
                .query(CommentRepository::comment).optional();
    }

    /** One page of the comments of the issue the viewer may see, oldest first (S0-AC-54). */
    public List<CommentRow> list(Viewer viewer, UUID issueId, long offset, int limit) {
        return jdbc.sql(SELECT + "where cm.issue_id = ? and " + IssueScope.SQL + VISIBLE
                        + " order by cm.created_at, cm.id offset ? limit ?")
                .params(concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), viewer.role().name(),
                        offset, limit))
                .query(CommentRepository::comment).list();
    }

    public long count(Viewer viewer, UUID issueId) {
        return jdbc.sql("select count(*) from comment cm join issue i on i.id = cm.issue_id "
                        + "where cm.issue_id = ? and " + IssueScope.SQL + VISIBLE)
                .params(concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), viewer.role().name()))
                .query(Long.class).single();
    }

    // Writes

    /** A new comment; returns its ID. The caller has locked the issue row first. */
    long insert(UUID issueId, long authorId, boolean internal, String content, Instant now) {
        return jdbc.sql("insert into comment (tenant_id, issue_id, author_id, internal, content, created_at) "
                        + "values (1, ?, ?, ?, ?, ?) returning id")
                .params(issueId, authorId, internal, content, Timestamp.from(now))
                .query(Long.class).single();
    }

    private static CommentRow comment(ResultSet rs, int row) throws SQLException {
        return new CommentRow(rs.getLong("id"), rs.getObject("issue_id", UUID.class), rs.getLong("author_id"),
                rs.getString("name"), Role.valueOf(rs.getString("role")), rs.getBoolean("internal"),
                rs.getString("content"), rs.getTimestamp("created_at").toInstant());
    }
}
