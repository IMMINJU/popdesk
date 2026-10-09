package io.popdesk.helpdesk.attachment;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Attachment metadata (table {@code attachment}). Every read takes the {@link Viewer} and finds the
 * attachment with the same query that checks the issue (conventions "Attachment paths"): the issue in
 * the viewer's scope ({@link IssueScope}), the attachment on that issue, stored, and for customers not
 * on an internal comment. Every other case is the same "not found" (COM-1).
 */
@Repository
public class AttachmentRepository {

    /** A stored attachment and where its contents are. */
    public record AttachmentRow(UUID id, UUID issueId, String fileName, long size, String contentType,
            String storage) {}

    private final JdbcClient jdbc;

    AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored attachment of the issue, if the viewer may see it. */
    public Optional<AttachmentRow> find(Viewer viewer, UUID issueId, UUID id) {
        return jdbc.sql("select a.id, a.issue_id, a.file_name, a.size, a.content_type, a.storage from attachment a "
                        + "join issue i on i.id = a.issue_id left join comment cm on cm.id = a.comment_id "
                        + "where a.id = ? and a.issue_id = ? and a.state = 'stored' "
                        + "and (cm.id is null or not cm.internal or ? <> 'CUSTOMER') and " + IssueScope.SQL)
                .params(concat(new Object[] {id, issueId, viewer.role().name()}, IssueScope.params(viewer)))
                .query(AttachmentRepository::attachment).optional();
    }

    private static AttachmentRow attachment(ResultSet rs, int row) throws SQLException {
        return new AttachmentRow(rs.getObject("id", UUID.class), rs.getObject("issue_id", UUID.class),
                rs.getString("file_name"), rs.getLong("size"), rs.getString("content_type"), rs.getString("storage"));
    }
}
