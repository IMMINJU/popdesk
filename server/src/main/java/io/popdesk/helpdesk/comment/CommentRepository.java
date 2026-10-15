package io.popdesk.helpdesk.comment;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Comments (tables {@code comment} and {@code comment_mention}). Every read takes the {@link Viewer}:
 * the issue must be one the viewer may see ({@link IssueScope}), and for customers internal comments
 * do not exist (S0-AC-53).
 */
@Repository
public class CommentRepository {

    /** An account a comment names besides its author; {@code photoUpdatedAt} is null without a profile photo. */
    public record Person(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /**
     * One comment with its author; {@code authorPhotoUpdatedAt} is null without a profile photo.
     * {@code editedAt} is null when the content never changed. A deleted comment has an empty content,
     * {@code deletedAt}, and {@code deletedBy} (COM-7). {@code mentions} is filled for staff only, in
     * name order; a customer gets an empty list (S4-AC-25).
     */
    public record CommentRow(long id, UUID issueId, long authorId, String authorName, Role authorRole,
            Instant authorPhotoUpdatedAt, boolean internal, String content, Instant createdAt, Instant editedAt,
            Instant deletedAt, Person deletedBy, List<Person> mentions) {

        public boolean deleted() {
            return deletedAt != null;
        }

        private CommentRow with(List<Person> mentioned) {
            return new CommentRow(id, issueId, authorId, authorName, authorRole, authorPhotoUpdatedAt, internal,
                    content, createdAt, editedAt, deletedAt, deletedBy, mentioned);
        }
    }

    private static final String SELECT = "select cm.id, cm.issue_id, cm.author_id, a.name, a.role, "
            + "ap.updated_at as author_photo_updated_at, cm.internal, cm.content, cm.created_at, cm.edited_at, "
            + "cm.deleted_at, cm.deleted_by, d.name as deleter_name, d.role as deleter_role, "
            + "dp.updated_at as deleter_photo_updated_at "
            + "from comment cm join issue i on i.id = cm.issue_id join account a on a.id = cm.author_id "
            + "left join account_photo ap on ap.account_id = cm.author_id "
            + "left join account d on d.id = cm.deleted_by "
            + "left join account_photo dp on dp.account_id = cm.deleted_by ";

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
                .query(CommentRepository::comment).optional()
                .map(c -> withMentions(viewer, List.of(c)).getFirst());
    }

    /** One page of the comments of the issue the viewer may see, oldest first (S0-AC-54). */
    public List<CommentRow> list(Viewer viewer, UUID issueId, long offset, int limit) {
        return withMentions(viewer, jdbc.sql(SELECT + "where cm.issue_id = ? and " + IssueScope.SQL + VISIBLE
                        + " order by cm.created_at, cm.id offset ? limit ?")
                .params(concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), viewer.role().name(),
                        offset, limit))
                .query(CommentRepository::comment).list());
    }

    public long count(Viewer viewer, UUID issueId) {
        return jdbc.sql("select count(*) from comment cm join issue i on i.id = cm.issue_id "
                        + "where cm.issue_id = ? and " + IssueScope.SQL + VISIBLE)
                .params(concat(concat(new Object[] {issueId}, IssueScope.params(viewer)), viewer.role().name()))
                .query(Long.class).single();
    }

    /**
     * The comments with the people each mentions, for staff. The comments are ones the viewer was
     * given by a scoped read; a customer's stay as they are, without mentions.
     */
    private List<CommentRow> withMentions(Viewer viewer, List<CommentRow> comments) {
        if (!viewer.staff() || comments.isEmpty()) {
            return comments;
        }
        Map<Long, List<Person>> mentioned = new HashMap<>();
        jdbc.sql("select m.comment_id, a.id, a.name, a.role, p.updated_at from comment_mention m "
                        + "join account a on a.id = m.account_id left join account_photo p on p.account_id = a.id "
                        + "where m.comment_id = any(?) order by a.name, a.id")
                .param(comments.stream().map(CommentRow::id).toArray(Long[]::new))
                .query(rs -> {
                    Timestamp photo = rs.getTimestamp(5);
                    mentioned.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>()).add(new Person(rs.getLong(2),
                            rs.getString(3), Role.valueOf(rs.getString(4)), photo == null ? null : photo.toInstant()));
                });
        return comments.stream().map(c -> c.with(List.copyOf(mentioned.getOrDefault(c.id(), List.of())))).toList();
    }

    // Reads for the service's writes. Package-private: the caller holds a comment it found in scope.

    /** The accounts the comment mentions. */
    List<Long> mentionedIds(long commentId) {
        return jdbc.sql("select account_id from comment_mention where comment_id = ? order by account_id")
                .param(commentId).query(Long.class).list();
    }

    // Writes

    /** A new comment; returns its ID. The caller has locked the issue row first. */
    void insert(long id, UUID issueId, long authorId, boolean internal, String content, Instant now) {
        jdbc.sql("insert into comment (id, tenant_id, issue_id, author_id, internal, content, created_at) "
                        + "values (?, 1, ?, ?, ?, ?, ?)")
                .params(id, issueId, authorId, internal, content, Timestamp.from(now))
                .update();
    }

    /**
     * The ID of the next comment, taken before its row is written: the images of a new comment are
     * attached to this ID first, because attachment rows change before any INSERT (architecture.md
     * "Concurrency and lock order").
     */
    long nextId() {
        return jdbc.sql("select nextval(pg_get_serial_sequence('comment', 'id'))").query(Long.class).single();
    }

    /** A new content and the time of the edit. The content the comment had is kept nowhere (S4-AC-18). */
    void updateContent(long id, String content, Instant now) {
        jdbc.sql("update comment set content = ?, edited_at = ? where id = ?")
                .params(content, Timestamp.from(now), id).update();
    }

    /**
     * Turns the comment into its trace (COM-7): the content emptied, who and when recorded. Returns 0
     * when it was deleted already, and then changes nothing (S4-AC-12).
     */
    int updateDeleted(long id, long deletedBy, Instant now) {
        return jdbc.sql("update comment set content = '', deleted_at = ?, deleted_by = ? "
                        + "where id = ? and deleted_at is null")
                .params(Timestamp.from(now), deletedBy, id).update();
    }

    /** Mentions of these accounts by the comment, in ID order. The caller has seen that none is there yet. */
    void insertMentions(long commentId, Collection<Long> accountIds, Instant now) {
        for (Long accountId : accountIds.stream().sorted().toList()) {
            jdbc.sql("insert into comment_mention (tenant_id, comment_id, account_id, created_at) values (1, ?, ?, ?)")
                    .params(commentId, accountId, Timestamp.from(now)).update();
        }
    }

    void deleteMentions(long commentId, Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return;
        }
        jdbc.sql("delete from comment_mention where comment_id = ? and account_id = any(?)")
                .params(commentId, accountIds.toArray(Long[]::new)).update();
    }

    private static CommentRow comment(ResultSet rs, int row) throws SQLException {
        Timestamp authorPhoto = rs.getTimestamp("author_photo_updated_at");
        Timestamp editedAt = rs.getTimestamp("edited_at");
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        long deletedById = rs.getLong("deleted_by");
        Person deletedBy = null;
        if (!rs.wasNull()) {
            Timestamp deleterPhoto = rs.getTimestamp("deleter_photo_updated_at");
            deletedBy = new Person(deletedById, rs.getString("deleter_name"), Role.valueOf(rs.getString("deleter_role")),
                    deleterPhoto == null ? null : deleterPhoto.toInstant());
        }
        return new CommentRow(rs.getLong("id"), rs.getObject("issue_id", UUID.class), rs.getLong("author_id"),
                rs.getString("name"), Role.valueOf(rs.getString("role")), authorPhoto == null ? null : authorPhoto.toInstant(),
                rs.getBoolean("internal"),
                rs.getString("content"), rs.getTimestamp("created_at").toInstant(),
                editedAt == null ? null : editedAt.toInstant(), deletedAt == null ? null : deletedAt.toInstant(),
                deletedBy, List.of());
    }

    /**
     * Deletes every comment of a purged issue and whom they mention (TRASH-3, S7A-AC-30). The caller holds
     * the issue row, and the issue's attachments point at no comment any more.
     */
    public void deleteOfIssue(UUID issueId) {
        jdbc.sql("delete from comment_mention m using comment c where c.id = m.comment_id and c.issue_id = ?")
                .param(issueId).update();
        jdbc.sql("delete from comment where issue_id = ?").param(issueId).update();
    }
}
