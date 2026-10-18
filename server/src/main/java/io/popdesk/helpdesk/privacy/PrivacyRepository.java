package io.popdesk.helpdesk.privacy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * What an account export reads (PII-3, S10A-AC-01 to S10A-AC-05): one account and what it wrote, by its
 * ID. Admins only, and the security filter chain has checked that, so nothing here narrows by a viewer.
 * No query reads another account's name or email: the rows hold the account's own values, the issue
 * key, and the company code.
 *
 * <p>The lists stream from a cursor, oldest first and then by ID; call them in a transaction, outside
 * one the driver loads every row at once.
 */
@Repository
class PrivacyRepository {

    /** Rows a cursor fetches at a time. */
    private static final int FETCH_SIZE = 500;

    record AccountData(long id, String email, String name, String role, String status, String locale,
            Instant createdAt, boolean anonymized) {}

    record IssueData(String key, String companyCode, String title, String content, String status,
            boolean privateIssue, Instant createdAt, Instant closedAt, Instant deletedAt) {}

    record CommentData(String issueKey, String content, boolean internal, Instant createdAt, Instant editedAt) {}

    record AttachmentData(String issueKey, Long commentId, String fileName, long size, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    PrivacyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The account; empty when there is none. */
    Optional<AccountData> findAccount(long accountId) {
        return jdbc.query("select id, email, name, role, status, locale, created_at, anonymized_at is not null "
                + "from account where id = ?", (rs, n) -> new AccountData(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), instant(rs, 7),
                        rs.getBoolean(8)), accountId).stream().findFirst();
    }

    /** The PNG of the account's profile photo; empty without one. */
    Optional<byte[]> findPhoto(long accountId) {
        return jdbc.query("select png from account_photo where account_id = ?", (rs, n) -> rs.getBytes(1),
                accountId).stream().findFirst();
    }

    /** The issues the account is the reporter of, filed on its behalf too; not purged ones (S10A-AC-02). */
    void streamIssues(long accountId, Consumer<IssueData> each) {
        stream("select c.code || '-' || i.number, c.code, i.title, i.content, i.status, i.is_private, i.created_at, "
                + "i.closed_at, i.deleted_at from issue i join company c on c.id = i.company_id "
                + "where i.reporter_id = ? and i.purged_at is null order by i.created_at, i.id", accountId,
                rs -> each.accept(new IssueData(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), instant(rs, 7), instant(rs, 8), instant(rs, 9))));
    }

    /** The comments the account wrote and did not delete, on issues not purged (S10A-AC-03). */
    void streamComments(long accountId, Consumer<CommentData> each) {
        stream("select c.code || '-' || i.number, cm.content, cm.internal, cm.created_at, cm.edited_at "
                + "from comment cm join issue i on i.id = cm.issue_id join company c on c.id = i.company_id "
                + "where cm.author_id = ? and cm.deleted_at is null and i.purged_at is null "
                + "order by cm.created_at, cm.id", accountId,
                rs -> each.accept(new CommentData(rs.getString(1), rs.getString(2), rs.getBoolean(3), instant(rs, 4),
                        instant(rs, 5))));
    }

    /**
     * The files the account uploaded that are stored on an issue that is not purged (S10A-AC-04): not
     * deleted ones, and not temporary images, which have no issue yet.
     */
    void streamAttachments(long accountId, Consumer<AttachmentData> each) {
        stream("select c.code || '-' || i.number, a.comment_id, a.file_name, a.size, a.created_at "
                + "from attachment a join issue i on i.id = a.issue_id join company c on c.id = i.company_id "
                + "where a.uploaded_by = ? and a.state = 'stored' and i.purged_at is null "
                + "order by a.created_at, a.id", accountId,
                rs -> each.accept(new AttachmentData(rs.getString(1), rs.getObject(2, Long.class),
                        rs.getString(3), rs.getLong(4), instant(rs, 5))));
    }

    /** A row handler that may throw. */
    @FunctionalInterface
    private interface Rows {
        void each(ResultSet rs) throws SQLException;
    }

    private void stream(String sql, long accountId, Rows rows) {
        jdbc.query(c -> {
            PreparedStatement s = c.prepareStatement(sql);
            s.setFetchSize(FETCH_SIZE);
            s.setLong(1, accountId);
            return s;
        }, (RowCallbackHandler) rows::each);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
