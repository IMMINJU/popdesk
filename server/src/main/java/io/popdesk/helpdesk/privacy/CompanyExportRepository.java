package io.popdesk.helpdesk.privacy;

import io.popdesk.helpdesk.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The company export's rows (PII-8, slice 10b design 0절 1 and 1절): the table {@code company_export}, and what the
 * ZIP holds of one company, read in the writer's snapshot. Unlike {@link PrivacyRepository} this reads the email and
 * name of other accounts: the company's member customers (S10B-AC-15). Staff are read with their role only, so the
 * writer can put the support team in their place (acceptance.md 5절 7).
 */
@Repository
class CompanyExportRepository {

    /** At most this many exports are listed (design.md 2절). */
    static final int LISTED = 50;

    /** A company: its ID, code, and name. */
    record Company(long id, String code, String name) {}

    /** An export's row, with the company's code and the current name of the account that asked. */
    record ExportRow(UUID id, long companyId, String companyCode, long requestedById, String requestedByName,
            boolean includeInternal, boolean includePrivate, String state, String failure, Long sizeBytes,
            Instant requestedAt, Instant finishedAt) {}

    /** Who wrote something: the account's ID, name, and role. */
    record Writer(long accountId, String name, Role role) {}

    /** An issue of the company, by ID and number. */
    record IssueRef(UUID id, int number) {}

    /** An issue's fields that a customer sees (S10B-AC-12). */
    record IssueData(String title, String content, String status, String priority, boolean privateIssue,
            Writer reporter, boolean filedBySupport, Instant createdAt, Instant updatedAt, Instant resolvedAt,
            Instant closedAt) {}

    record CommentData(String content, boolean internal, Instant createdAt, Instant editedAt, Writer author) {}

    record TaskData(long id, String title, String status, LocalDate dueDate) {}

    record NoteData(long taskId, String content, Instant createdAt, Writer author) {}

    /** An attachment row: what reading its file later needs, and its name for the ZIP and the missing list. */
    record AttachmentData(UUID id, String fileName, long size, String storage, String storageLocation) {}

    private static final String EXPORT_SELECT = "select e.id, e.company_id, c.code, e.requested_by, a.name, "
            + "e.include_internal, e.include_private, e.state, e.failure, e.size_bytes, e.requested_at, e.finished_at "
            + "from company_export e join company c on c.id = e.company_id join account a on a.id = e.requested_by ";

    private final JdbcClient jdbc;

    CompanyExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Exports

    Optional<Company> findCompany(String code) {
        return jdbc.sql("select id, code, name from company where code = ?").param(code)
                .query((rs, n) -> new Company(rs.getLong("id"), rs.getString("code"), rs.getString("name")))
                .optional();
    }

    /** A new export, {@code RUNNING}. The unique index refuses a second one while one runs. */
    void insert(UUID id, long companyId, long requestedBy, boolean includeInternal, boolean includePrivate,
            Instant requestedAt) {
        jdbc.sql("insert into company_export (id, tenant_id, company_id, requested_by, include_internal, "
                        + "include_private, state, requested_at) values (?, 1, ?, ?, ?, ?, 'RUNNING', ?)")
                .params(id, companyId, requestedBy, includeInternal, includePrivate, Timestamp.from(requestedAt))
                .update();
    }

    Optional<ExportRow> find(UUID id) {
        return jdbc.sql(EXPORT_SELECT + "where e.id = ?").param(id).query(CompanyExportRepository::export).optional();
    }

    /** The company's last {@value #LISTED} exports, the latest asked for first. */
    List<ExportRow> list(long companyId) {
        return jdbc.sql(EXPORT_SELECT + "where e.company_id = ? order by e.requested_at desc, e.id limit ?")
                .params(companyId, LISTED).query(CompanyExportRepository::export).list();
    }

    /** A {@code RUNNING} export becomes {@code DONE}; false when it was not {@code RUNNING}. */
    boolean updateDone(UUID id, long sizeBytes, Instant now) {
        return jdbc.sql("update company_export set state = 'DONE', size_bytes = ?, finished_at = ? "
                        + "where id = ? and state = 'RUNNING'")
                .params(sizeBytes, Timestamp.from(now), id).update() == 1;
    }

    /** A {@code RUNNING} export becomes {@code FAILED}; false when it was not {@code RUNNING}. */
    boolean updateFailed(UUID id, String failure, Instant now) {
        return jdbc.sql("update company_export set state = 'FAILED', failure = ?, finished_at = ? "
                        + "where id = ? and state = 'RUNNING'")
                .params(failure, Timestamp.from(now), id).update() == 1;
    }

    /** Every {@code RUNNING} export becomes {@code FAILED} with {@code INTERRUPTED}; gives how many. */
    int updateInterrupted(Instant now) {
        return jdbc.sql("update company_export set state = 'FAILED', failure = 'INTERRUPTED', finished_at = ? "
                        + "where state = 'RUNNING'")
                .param(Timestamp.from(now)).update();
    }

    /** The {@code DONE} exports. */
    List<UUID> listDone() {
        return jdbc.sql("select id from company_export where state = 'DONE'").query(UUID.class).list();
    }

    /** The {@code DONE} exports that became so at or before {@code finishedUntil}. */
    List<UUID> listDoneUntil(Instant finishedUntil) {
        return jdbc.sql("select id from company_export where state = 'DONE' and finished_at <= ? order by finished_at")
                .param(Timestamp.from(finishedUntil)).query(UUID.class).list();
    }

    /** A {@code DONE} export becomes {@code EXPIRED}; false when it was not {@code DONE}. */
    boolean updateExpired(UUID id) {
        return jdbc.sql("update company_export set state = 'EXPIRED' where id = ? and state = 'DONE'").param(id)
                .update() == 1;
    }

    // What the ZIP holds, read in the writer's snapshot

    /** The company's member customers now, email and name (S10B-AC-15). */
    List<String[]> listCustomers(long companyId) {
        return jdbc.sql("select a.email, a.name from company_member m join account a on a.id = m.account_id "
                        + "where m.company_id = ? order by a.id")
                .param(companyId).query((rs, n) -> new String[] {rs.getString("email"), rs.getString("name")})
                .list();
    }

    /** The company's issues that are not in the trash or purged, private ones only if asked (S10B-AC-16). */
    List<IssueRef> listIssues(long companyId, boolean includePrivate) {
        return jdbc.sql("select id, number from issue where company_id = ? and deleted_at is null "
                        + (includePrivate ? "" : "and not is_private ") + "order by number")
                .param(companyId).query((rs, n) -> new IssueRef(rs.getObject("id", UUID.class), rs.getInt("number")))
                .list();
    }

    IssueData findIssue(UUID id) {
        return jdbc.sql("select i.title, i.content, i.status, i.priority, i.is_private, i.created_by, i.created_at, "
                        + "i.updated_at, i.resolved_at, i.closed_at, a.id as writer_id, a.name as writer_name, "
                        + "a.role as writer_role from issue i join account a on a.id = i.reporter_id where i.id = ?")
                .param(id)
                .query((rs, n) -> new IssueData(rs.getString("title"), rs.getString("content"),
                        rs.getString("status"), rs.getString("priority"), rs.getBoolean("is_private"), writer(rs),
                        rs.getObject("created_by") != null, instant(rs, "created_at"), instant(rs, "updated_at"),
                        instant(rs, "resolved_at"), instant(rs, "closed_at")))
                .single();
    }

    /** The issue's comments that are not deleted, internal ones only if asked, oldest first (S10B-AC-13). */
    List<CommentData> listComments(UUID issueId, boolean includeInternal) {
        return jdbc.sql("select c.content, c.internal, c.created_at, c.edited_at, a.id as writer_id, "
                        + "a.name as writer_name, a.role as writer_role from comment c "
                        + "join account a on a.id = c.author_id where c.issue_id = ? and c.deleted_at is null "
                        + (includeInternal ? "" : "and not c.internal ")
                        + "order by c.created_at, c.id")
                .param(issueId)
                .query((rs, n) -> new CommentData(rs.getString("content"), rs.getBoolean("internal"),
                        instant(rs, "created_at"), instant(rs, "edited_at"), writer(rs)))
                .list();
    }

    /** The issue's tasks that are not deleted, oldest first. */
    List<TaskData> listTasks(UUID issueId) {
        return jdbc.sql("select id, title, status, due_date from task where issue_id = ? and deleted_at is null "
                        + "order by created_at, id")
                .param(issueId)
                .query((rs, n) -> new TaskData(rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                        rs.getObject("due_date", LocalDate.class)))
                .list();
    }

    /** The notes written on these tasks that are not deleted, oldest first; not the changes. */
    List<NoteData> listNotes(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select n.task_id, n.content, n.created_at, a.id as writer_id, a.name as writer_name, "
                        + "a.role as writer_role from task_note n join account a on a.id = n.author_id "
                        + "where n.task_id = any(?) and n.kind = 'NOTE' and n.deleted_at is null "
                        + "order by n.created_at, n.id")
                .param(taskIds.toArray(Long[]::new))
                .query((rs, n) -> new NoteData(rs.getLong("task_id"), rs.getString("content"),
                        instant(rs, "created_at"), writer(rs)))
                .list();
    }

    /**
     * The files of the issue and of the comments the ZIP holds (S10B-AC-14): stored and not deleted, those of the
     * issue itself (content images among them) and those of comments that are not deleted, of internal comments only
     * if asked. Oldest first.
     */
    List<AttachmentData> listAttachments(UUID issueId, boolean includeInternal) {
        return jdbc.sql("select t.id, t.file_name, t.size, t.storage, t.storage_location from attachment t "
                        + "left join comment c on c.id = t.comment_id where t.issue_id = ? and t.state = 'stored' "
                        + "and t.deleted_at is null and (t.comment_id is null or (c.issue_id = t.issue_id "
                        + "and c.deleted_at is null" + (includeInternal ? "" : " and not c.internal") + ")) "
                        + "order by t.created_at, t.id")
                .param(issueId)
                .query((rs, n) -> new AttachmentData(rs.getObject("id", UUID.class), rs.getString("file_name"),
                        rs.getLong("size"), rs.getString("storage"), rs.getString("storage_location")))
                .list();
    }

    private static ExportRow export(ResultSet rs, int n) throws SQLException {
        return new ExportRow(rs.getObject("id", UUID.class), rs.getLong("company_id"), rs.getString("code"),
                rs.getLong("requested_by"), rs.getString("name"), rs.getBoolean("include_internal"),
                rs.getBoolean("include_private"), rs.getString("state"), rs.getString("failure"),
                rs.getObject("size_bytes", Long.class), instant(rs, "requested_at"), instant(rs, "finished_at"));
    }

    private static Writer writer(ResultSet rs) throws SQLException {
        return new Writer(rs.getLong("writer_id"), rs.getString("writer_name"),
                Role.valueOf(rs.getString("writer_role")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
