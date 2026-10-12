package io.popdesk.helpdesk.attachment;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueScope;
import jakarta.persistence.EntityManager;
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
 * Attachment metadata (table {@code attachment}). Every read takes the {@link Viewer} and finds the
 * attachment with the same query that checks the issue (conventions "Attachment paths"): the issue in
 * the viewer's scope ({@link IssueScope}), the attachment on that issue, and for customers not on an
 * internal comment. Every other case is the same "not found" (COM-1).
 *
 * <p>States: {@code pending} until the upload's business transaction stores it, {@code stored}, then
 * {@code purging} once a delete is committed and {@code purged} when the file is gone (ATT-10). Writes
 * that change the state are conditional on the state they expect, so of two writers only one wins.
 */
@Repository
public class AttachmentRepository {

    /** An attachment as the lists and downloads show it; a photo time is null without a profile photo. */
    public record AttachmentRow(UUID id, UUID issueId, Long commentId, boolean internal, String fileName, long size,
            String contentType, String state, long uploadedById, String uploadedByName, Role uploadedByRole,
            Instant uploadedByPhotoUpdatedAt, Instant createdAt, Instant deletedAt, Long deletedById,
            String deletedByName, Role deletedByRole, Instant deletedByPhotoUpdatedAt) {

        public boolean deleted() {
            return !STORED.equals(state);
        }
    }

    static final String PENDING = "pending";
    static final String STORED = "stored";

    private static final String SELECT = "select a.id, a.issue_id, a.comment_id, coalesce(cm.internal, false) as internal, "
            + "a.file_name, a.size, a.content_type, a.state, a.uploaded_by, u.name as uploader_name, "
            + "u.role as uploader_role, up.updated_at as uploader_photo_updated_at, a.created_at, a.deleted_at, "
            + "a.deleted_by, d.name as deleter_name, d.role as deleter_role, "
            + "dp.updated_at as deleter_photo_updated_at from attachment a join issue i on i.id = a.issue_id "
            + "left join comment cm on cm.id = a.comment_id join account u on u.id = a.uploaded_by "
            + "left join account d on d.id = a.deleted_by "
            + "left join account_photo up on up.account_id = a.uploaded_by "
            + "left join account_photo dp on dp.account_id = a.deleted_by ";

    /** Attachments of internal comments only for staff; one parameter, the viewer's role. */
    private static final String VISIBLE = " and (cm.id is null or not cm.internal or ? <> 'CUSTOMER')";

    /**
     * What a list shows: stored attachments, and the traces of those a person deleted (COM-7). A row the
     * cleanup ended was never stored, so it has no one who deleted it and is not shown.
     */
    private static final String LISTED = " and (a.state = 'stored' or a.deleted_by is not null)";

    private final JdbcClient jdbc;
    private final EntityManager entities;

    AttachmentRepository(JdbcClient jdbc, EntityManager entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    /** The stored attachment of the issue, if the viewer may see it. */
    public Optional<AttachmentRow> find(Viewer viewer, UUID issueId, UUID id) {
        return jdbc.sql(SELECT + "where a.id = ? and a.issue_id = ? and a.state = 'stored' and " + IssueScope.SQL
                        + VISIBLE)
                .params(concat(concat(new Object[] {id, issueId}, IssueScope.params(viewer)), viewer.role().name()))
                .query(AttachmentRepository::attachment).optional();
    }

    /** One page of the attachments of the issue the viewer may see, oldest first, with deleted ones as traces. */
    public List<AttachmentRow> list(Viewer viewer, UUID issueId, long offset, int limit) {
        return jdbc.sql(SELECT + "where a.issue_id = ? and " + IssueScope.SQL + VISIBLE + LISTED
                        + " order by a.created_at, a.id offset ? limit ?")
                .params(concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), viewer.role().name(),
                        offset, limit))
                .query(AttachmentRepository::attachment).list();
    }

    public long count(Viewer viewer, UUID issueId) {
        return jdbc.sql("select count(*) from attachment a join issue i on i.id = a.issue_id "
                        + "left join comment cm on cm.id = a.comment_id where a.issue_id = ? and " + IssueScope.SQL
                        + VISIBLE + LISTED)
                .params(concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), viewer.role().name()))
                .query(Long.class).single();
    }

    // Sums for the limits (ATT-2, ATT-3). Package-private: they read without a viewer, for the service's checks.

    /** Bytes of the stored public attachments of an issue: on the issue and on its public comments. */
    long publicBytes(UUID issueId) {
        return jdbc.sql("select coalesce(sum(a.size), 0) from attachment a left join comment cm on cm.id = a.comment_id "
                        + "where a.issue_id = ? and a.state = 'stored' and (cm.id is null or not cm.internal)")
                .params(issueId).query(Long.class).single();
    }

    /** Bytes of every stored attachment of the team. */
    long storedBytes() {
        return jdbc.sql("select coalesce(sum(size), 0) from attachment where state = 'stored'")
                .query(Long.class).single();
    }

    /** The stored attachments of a comment, in ID order. The caller found the comment in a viewer's scope. */
    List<UUID> storedOfComment(long commentId) {
        return jdbc.sql("select id from attachment where comment_id = ? and state = 'stored' order by id")
                .param(commentId).query(UUID.class).list();
    }

    // Writes

    /** Step ①: the new attachment's row in state {@code pending}. Joins the caller's transaction. */
    void persistPending(PendingAttachment attachment) {
        entities.persist(attachment);
    }

    /**
     * Step ③: {@code pending} to {@code stored} on the issue. Returns 0 when the row is no longer pending,
     * because the cleanup ended it first (S0-AC-63).
     */
    int updateStored(UUID id, UUID issueId) {
        return jdbc.sql("update attachment set state = 'stored', issue_id = ? where id = ? and state = 'pending'")
                .params(issueId, id).update();
    }

    /**
     * Whether the row was ended: it is {@code purging} or {@code purged}. False for a {@code pending} or
     * {@code stored} row and when there is no such row.
     */
    boolean isEnded(UUID id) {
        return jdbc.sql("select count(*) from attachment where id = ? and state in ('purging', 'purged')")
                .params(id).query(Long.class).single() == 1;
    }

    /**
     * The first step of a delete: {@code stored} to {@code purging}, the file name cleared (it can hold
     * personal data), who and when recorded (S0-AC-61). Returns 0 when it was not stored any more.
     */
    int updatePurging(UUID id, long deletedBy, Instant at) {
        return jdbc.sql("update attachment set state = 'purging', file_name = null, deleted_at = ?, deleted_by = ? "
                        + "where id = ? and state = 'stored'")
                .params(Timestamp.from(at), deletedBy, id).update();
    }

    /** The last step of a delete, once the file is gone: {@code purging} to {@code purged}. */
    int updatePurged(UUID id) {
        return jdbc.sql("update attachment set state = 'purged' where id = ? and state = 'purging'")
                .params(id).update();
    }

    private static AttachmentRow attachment(ResultSet rs, int row) throws SQLException {
        long commentId = rs.getLong("comment_id");
        Long comment = rs.wasNull() ? null : commentId;
        long deletedById = rs.getLong("deleted_by");
        Long deletedBy = rs.wasNull() ? null : deletedById;
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        String deleterRole = rs.getString("deleter_role");
        Timestamp uploaderPhoto = rs.getTimestamp("uploader_photo_updated_at");
        Timestamp deleterPhoto = rs.getTimestamp("deleter_photo_updated_at");
        return new AttachmentRow(rs.getObject("id", UUID.class), rs.getObject("issue_id", UUID.class), comment,
                rs.getBoolean("internal"), rs.getString("file_name"), rs.getLong("size"), rs.getString("content_type"),
                rs.getString("state"), rs.getLong("uploaded_by"), rs.getString("uploader_name"),
                Role.valueOf(rs.getString("uploader_role")), uploaderPhoto == null ? null : uploaderPhoto.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                deletedAt == null ? null : deletedAt.toInstant(), deletedBy, rs.getString("deleter_name"),
                deleterRole == null ? null : Role.valueOf(deleterRole),
                deleterPhoto == null ? null : deleterPhoto.toInstant());
    }
}
