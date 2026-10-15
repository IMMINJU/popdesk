package io.popdesk.helpdesk.task;

import static io.popdesk.helpdesk.issue.IssueScope.concat;

import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.issue.IssueScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * Tasks of issues and their notes (tables {@code task} and {@code task_note}, TASK-1 to TASK-7). The reads
 * under an issue take the issue's ID, which the caller found in the viewer's scope first (S6B-AC-40); a
 * task of another issue is not found (S6B-AC-41). The caller's own tasks apply {@link IssueScope} in the
 * query, like the issue list (S6B-AC-62).
 */
@Repository
public class TaskRepository {

    /** The status of a task (TASK-2); stored in {@code task.status}, so the names never change. */
    public enum Status {
        OPEN,
        DONE
    }

    /** What an entry of a task is; stored in {@code task_note.kind}. */
    public enum Kind {
        /** A note a person wrote (TASK-3). */
        NOTE,
        /** A change of one field of the task (TASK-5). */
        CHANGE
    }

    /** An account a task names; {@code photoUpdatedAt} is null without a profile photo. */
    public record Person(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /**
     * One task with the people it names. {@code assignee}, {@code dueDate}, {@code doneBy}, {@code doneAt}
     * are null when the task has none; a deleted task has an empty title, no assignee and no due date, and
     * {@code deletedAt} and {@code deletedBy} (COM-7).
     */
    public record TaskRow(long id, UUID issueId, String title, Status status, Person assignee, LocalDate dueDate,
            Person createdBy, Instant createdAt, Person doneBy, Instant doneAt, Person deletedBy, Instant deletedAt) {

        public boolean deleted() {
            return deletedAt != null;
        }

        public Long assigneeId() {
            return assignee == null ? null : assignee.accountId();
        }
    }

    /**
     * One entry of a task. For {@link Kind#CHANGE}, {@code field} names the field and {@code oldValue} and
     * {@code newValue} hold the values as stored: for {@code ASSIGNEE} the account IDs, which
     * {@code oldPerson} and {@code newPerson} give as people. For {@link Kind#NOTE} those five are null.
     */
    public record NoteRow(long id, long taskId, Kind kind, Person author, String content, String field,
            String oldValue, String newValue, Person oldPerson, Person newPerson, Instant createdAt,
            Instant editedAt, Person deletedBy, Instant deletedAt) {

        public boolean deleted() {
            return deletedAt != null;
        }
    }

    /** A row of the caller's own tasks (S6B-AC-60). */
    public record MyTaskRow(long id, String title, Status status, LocalDate dueDate, Instant doneAt,
            String companyCode, String companyName, int issueNumber, String issueTitle, IssueStatus issueStatus) {

        public String issueKey() {
            return companyCode + "-" + issueNumber;
        }
    }

    /** How many tasks of an issue, or entries of a task: those that count toward the first limit, and all. */
    public record Counts(long kept, long all) {}

    /** An account and its photo as columns of {@code SELECT}, from the account ID in {@code column}. */
    private static String personColumns(String column, String alias) {
        return column + " as " + alias + "_id, " + alias + ".name as " + alias + "_name, " + alias + ".role as "
                + alias + "_role, " + alias + "p.updated_at as " + alias + "_photo";
    }

    /** The joins of {@link #personColumns}. */
    private static String personJoins(String column, String alias) {
        return " left join account " + alias + " on " + alias + ".id = " + column + " left join account_photo "
                + alias + "p on " + alias + "p.account_id = " + column;
    }

    private static final String TASK_SELECT = "select t.id, t.issue_id, t.title, t.status, t.due_date, t.created_at, "
            + "t.done_at, t.deleted_at, " + personColumns("t.assignee_id", "asg") + ", "
            + personColumns("t.created_by", "cb") + ", " + personColumns("t.done_by", "dn") + ", "
            + personColumns("t.deleted_by", "dl") + " from task t"
            + personJoins("t.assignee_id", "asg") + personJoins("t.created_by", "cb")
            + personJoins("t.done_by", "dn") + personJoins("t.deleted_by", "dl") + " ";

    /** An ASSIGNEE change keeps account IDs as text; anything else in the field is no account. */
    private static String changedAccount(String value) {
        return "case when n.field = 'ASSIGNEE' and " + value + " ~ '^[0-9]{1,18}$' then cast(" + value
                + " as bigint) end";
    }

    private static final String NOTE_SELECT = "select n.id, n.task_id, n.kind, n.content, n.field, n.old_value, "
            + "n.new_value, n.created_at, n.edited_at, n.deleted_at, " + personColumns("n.author_id", "au") + ", "
            + personColumns("n.deleted_by", "dl") + ", " + personColumns(changedAccount("n.old_value"), "op") + ", "
            + personColumns(changedAccount("n.new_value"), "np") + " from task_note n"
            + personJoins("n.author_id", "au") + personJoins("n.deleted_by", "dl")
            + personJoins(changedAccount("n.old_value"), "op") + personJoins(changedAccount("n.new_value"), "np")
            + " ";

    /**
     * The caller's own tasks (TASK-7) as {@code FROM} and {@code WHERE}, with the parameters of
     * {@link #myTasksParams}: tasks of the account that are not deleted and have the status, of issues
     * that are not {@code CLOSED} and that the account may see now, by the scope of the issue list.
     */
    private static final String MY_TASKS = "from task t join issue i on i.id = t.issue_id "
            + "join company c on c.id = i.company_id where t.assignee_id = ? and t.deleted_at is null "
            + "and t.status = ? and i.status <> 'CLOSED' and " + IssueScope.SQL;

    /**
     * Open tasks an account is the assignee of, among the issues that are not {@code CLOSED} (ACCT-7,
     * S6B-AC-75), as SQL on {@code t} and its issue {@code i} with one parameter, the account.
     */
    private static final String OPEN_ASSIGNMENT = "t.assignee_id = ? and t.status = 'OPEN' and t.deleted_at is null "
            + "and i.status <> 'CLOSED'";

    private final JdbcClient jdbc;

    TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every task of the issue, deleted ones too, in the order they were made (S6B-AC-24). */
    public List<TaskRow> listOfIssue(UUID issueId) {
        return jdbc.sql(TASK_SELECT + "where t.issue_id = ? order by t.id").param(issueId)
                .query(TaskRepository::task).list();
    }

    /** The task of the issue with this ID; empty for a task of another issue (S6B-AC-41). */
    public Optional<TaskRow> find(UUID issueId, long id) {
        return jdbc.sql(TASK_SELECT + "where t.id = ? and t.issue_id = ?").params(id, issueId)
                .query(TaskRepository::task).optional();
    }

    /** Every entry of these tasks, in the order they were written, by task (S6B-AC-24). */
    public Map<Long, List<NoteRow>> notesOf(Collection<Long> taskIds) {
        Map<Long, List<NoteRow>> notes = new HashMap<>();
        if (taskIds.isEmpty()) {
            return notes;
        }
        jdbc.sql(NOTE_SELECT + "where n.task_id = any(?) order by n.id").param(taskIds.toArray(Long[]::new))
                .query(TaskRepository::note).list()
                .forEach(n -> notes.computeIfAbsent(n.taskId(), id -> new ArrayList<>()).add(n));
        return notes;
    }

    /** The entry of the task with this ID; empty for an entry of another task (S6B-AC-41). */
    public Optional<NoteRow> findNote(long taskId, long id) {
        return jdbc.sql(NOTE_SELECT + "where n.id = ? and n.task_id = ?").params(id, taskId)
                .query(TaskRepository::note).optional();
    }

    /**
     * How many tasks that are {@code OPEN} and not deleted each of these issues has (S6B-AC-70); an issue
     * without one is not in the map. One read for a page of issues.
     */
    public Map<UUID, Long> openCounts(Collection<UUID> issueIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (issueIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("select issue_id, count(*) from task where issue_id = any(?) and status = 'OPEN' "
                        + "and deleted_at is null group by issue_id")
                .param(issueIds.toArray(UUID[]::new))
                .query(rs -> {
                    counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
                });
        return counts;
    }

    /** The tasks of an issue: those not deleted, and all (S6B-AC-08). The caller holds the issue row lock. */
    Counts countTasks(UUID issueId) {
        return jdbc.sql("select count(*) filter (where deleted_at is null), count(*) from task where issue_id = ?")
                .param(issueId).query((rs, n) -> new Counts(rs.getLong(1), rs.getLong(2))).single();
    }

    /**
     * The entries of a task: its notes that are not deleted, and every entry, deleted notes and changes
     * included (S6B-AC-23). The caller holds the issue row lock.
     */
    Counts countNotes(long taskId) {
        return jdbc.sql("select count(*) filter (where kind = 'NOTE' and deleted_at is null), count(*) "
                        + "from task_note where task_id = ?")
                .param(taskId).query((rs, n) -> new Counts(rs.getLong(1), rs.getLong(2))).single();
    }

    /** One page of the caller's own tasks with this status, in the order of TASK-7 and S6B-AC-61. */
    public List<MyTaskRow> listMine(Viewer viewer, Status status, long offset, int limit) {
        String order = status == Status.OPEN ? "t.due_date asc nulls last, t.created_at, t.id"
                : "t.done_at desc, t.id desc";
        return jdbc.sql("select t.id, t.title, t.status, t.due_date, t.done_at, c.code, c.name, i.number, "
                        + "i.title as issue_title, i.status as issue_status " + MY_TASKS + " order by " + order
                        + " offset ? limit ?")
                .params(concat(myTasksParams(viewer, status), offset, limit))
                .query((rs, n) -> new MyTaskRow(rs.getLong("id"), rs.getString("title"),
                        Status.valueOf(rs.getString("status")), rs.getObject("due_date", LocalDate.class),
                        instant(rs, "done_at"), rs.getString("code"), rs.getString("name"), rs.getInt("number"),
                        rs.getString("issue_title"), IssueStatus.valueOf(rs.getString("issue_status"))))
                .list();
    }

    /** How many tasks {@link #listMine} has over all its pages. */
    public long countMine(Viewer viewer, Status status) {
        return jdbc.sql("select count(*) " + MY_TASKS).params(myTasksParams(viewer, status))
                .query(Long.class).single();
    }

    private static Object[] myTasksParams(Viewer viewer, Status status) {
        return concat(new Object[] {viewer.accountId(), status.name()}, IssueScope.params(viewer));
    }

    /**
     * How many open tasks of issues that are not {@code CLOSED} have the account as their assignee, among
     * the issues the viewer may see; with {@code companyId}, in that company only (S6B-AC-75). It counts
     * what {@link #updateAssigneesCleared} clears for the same account and company.
     */
    public long countOpenAssignments(Viewer viewer, long accountId, Long companyId) {
        Object[] params = concat(IssueScope.params(viewer), accountId);
        return jdbc.sql("select count(*) from task t join issue i on i.id = t.issue_id where " + IssueScope.SQL
                        + " and " + OPEN_ASSIGNMENT + (companyId == null ? "" : " and i.company_id = ?"))
                .params(companyId == null ? params : concat(params, companyId))
                .query(Long.class).single();
    }

    // Writes. The caller has locked the issue row first (architecture.md "Concurrency and lock order").

    /** A new {@code OPEN} task; returns its ID. */
    long insert(UUID issueId, String title, Long assigneeId, LocalDate dueDate, long createdBy, Instant now) {
        return jdbc.sql("insert into task (tenant_id, issue_id, title, status, assignee_id, due_date, created_by, "
                        + "created_at) values (1, ?, ?, 'OPEN', ?, ?, ?, ?) returning id")
                .params(issueId, title, assigneeId, dueDate, createdBy, Timestamp.from(now))
                .query(Long.class).single();
    }

    /** Stores the fields of a task as they are after an edit, with who made it {@code DONE} and when. */
    void update(long id, String title, Status status, Long assigneeId, LocalDate dueDate, Long doneBy,
            Instant doneAt) {
        jdbc.sql("update task set title = ?, status = ?, assignee_id = ?, due_date = ?, done_by = ?, done_at = ? "
                        + "where id = ?")
                .params(title, status.name(), assigneeId, dueDate, doneBy, doneAt == null ? null : Timestamp.from(doneAt),
                        id)
                .update();
    }

    /**
     * Turns the task into its trace (COM-7, S6B-AC-10): the title emptied, the assignee and the due date
     * cleared, who and when recorded; its notes emptied and marked deleted, those deleted already kept as
     * they are; and the values of its changes removed. Returns 0 when it was deleted already, and then
     * changes nothing.
     */
    int updateDeleted(long id, long deletedBy, Instant now) {
        int changed = jdbc.sql("update task set title = '', assignee_id = null, due_date = null, deleted_at = ?, "
                        + "deleted_by = ? where id = ? and deleted_at is null")
                .params(Timestamp.from(now), deletedBy, id).update();
        if (changed == 0) {
            return 0;
        }
        jdbc.sql("update task_note set content = '', deleted_at = ?, deleted_by = ? "
                        + "where task_id = ? and kind = 'NOTE' and deleted_at is null")
                .params(Timestamp.from(now), deletedBy, id).update();
        jdbc.sql("update task_note set old_value = null, new_value = null where task_id = ? and kind = 'CHANGE'")
                .param(id).update();
        return changed;
    }

    /** A note by a person; returns its ID. */
    long insertNote(long taskId, long authorId, String content, Instant now) {
        return jdbc.sql("insert into task_note (tenant_id, task_id, kind, author_id, content, created_at) "
                        + "values (1, ?, 'NOTE', ?, ?, ?) returning id")
                .params(taskId, authorId, content, Timestamp.from(now))
                .query(Long.class).single();
    }

    /** A change of one field of the task (S6B-AC-30). */
    void insertChange(long taskId, long authorId, String field, String oldValue, String newValue, Instant now) {
        jdbc.sql("insert into task_note (tenant_id, task_id, kind, author_id, content, field, old_value, new_value, "
                        + "created_at) values (1, ?, 'CHANGE', ?, '', ?, ?, ?, ?)")
                .params(taskId, authorId, field, oldValue, newValue, Timestamp.from(now))
                .update();
    }

    /** A new content of a note and the time of the edit (S6B-AC-21). */
    void updateNoteContent(long id, String content, Instant now) {
        jdbc.sql("update task_note set content = ?, edited_at = ? where id = ?")
                .params(content, Timestamp.from(now), id).update();
    }

    /** Turns the note into its trace (COM-7, S6B-AC-22); 0 when it was deleted already. */
    int updateNoteDeleted(long id, long deletedBy, Instant now) {
        return jdbc.sql("update task_note set content = '', deleted_at = ?, deleted_by = ? "
                        + "where id = ? and deleted_at is null")
                .params(Timestamp.from(now), deletedBy, id).update();
    }

    /**
     * Clears the assignee of every open task of an issue that is not {@code CLOSED} and has this account
     * as its assignee; with {@code companyId}, of that company's issues only (ACCT-7, S6B-AC-76). One
     * conditional UPDATE, without locking the issue rows: a task that got another assignee, was done, or
     * whose issue was closed meanwhile no longer matches and stays as it is. A task of an issue in the trash
     * keeps its assignee, to be restored as it was (slice 7a acceptance section 5, 6).
     *
     * @return the tasks that were cleared, in ID order
     */
    public List<Long> updateAssigneesCleared(long accountId, Long companyId) {
        List<Object> params = new ArrayList<>(List.of(accountId));
        if (companyId != null) {
            params.add(companyId);
        }
        return jdbc.sql("update task t set assignee_id = null from issue i where i.id = t.issue_id and i.deleted_at is null and "
                        + OPEN_ASSIGNMENT + (companyId == null ? "" : " and i.company_id = ?") + " returning t.id")
                .params(params)
                .query(Long.class).list().stream().sorted().toList();
    }

    private static TaskRow task(ResultSet rs, int row) throws SQLException {
        return new TaskRow(rs.getLong("id"), rs.getObject("issue_id", UUID.class), rs.getString("title"),
                Status.valueOf(rs.getString("status")), person(rs, "asg"), rs.getObject("due_date", LocalDate.class),
                person(rs, "cb"), instant(rs, "created_at"), person(rs, "dn"), instant(rs, "done_at"),
                person(rs, "dl"), instant(rs, "deleted_at"));
    }

    private static NoteRow note(ResultSet rs, int row) throws SQLException {
        return new NoteRow(rs.getLong("id"), rs.getLong("task_id"), Kind.valueOf(rs.getString("kind")),
                person(rs, "au"), rs.getString("content"), rs.getString("field"), rs.getString("old_value"),
                rs.getString("new_value"), person(rs, "op"), person(rs, "np"), instant(rs, "created_at"),
                instant(rs, "edited_at"), person(rs, "dl"), instant(rs, "deleted_at"));
    }

    /** The person of {@link #personColumns} under {@code alias}; null when the ID is null or no account has it. */
    private static Person person(ResultSet rs, String alias) throws SQLException {
        long id = rs.getLong(alias + "_id");
        if (rs.wasNull() || rs.getString(alias + "_name") == null) {
            return null;
        }
        return new Person(id, rs.getString(alias + "_name"), Role.valueOf(rs.getString(alias + "_role")),
                instant(rs, alias + "_photo"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }
}
