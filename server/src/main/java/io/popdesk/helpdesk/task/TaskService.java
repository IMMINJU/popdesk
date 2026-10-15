package io.popdesk.helpdesk.task;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.InstallationZone;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.DueDates;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.issue.IssueKey;
import io.popdesk.helpdesk.issue.IssueNotifications;
import io.popdesk.helpdesk.issue.IssueRepository;
import io.popdesk.helpdesk.issue.IssueRepository.IssueRow;
import io.popdesk.helpdesk.task.TaskChanges.Change;
import io.popdesk.helpdesk.task.TaskRepository.Counts;
import io.popdesk.helpdesk.task.TaskRepository.Kind;
import io.popdesk.helpdesk.task.TaskRepository.MyTaskRow;
import io.popdesk.helpdesk.task.TaskRepository.NoteRow;
import io.popdesk.helpdesk.task.TaskRepository.Status;
import io.popdesk.helpdesk.task.TaskRepository.TaskRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Tasks of issues and their notes (TASK-1 to TASK-7). Checks follow conventions "Order of checks": the
 * rate limit, the issue in the caller's scope, then the task and the note within it (404), permission
 * (403), input (400), the state of the task and of the issue (400), then the limits (400).
 *
 * <p>Every write is one transaction, retried once on a lock conflict, that locks the issue row
 * {@code FOR NO KEY UPDATE} first (STATUS-7, slice 6b design section 0, 1): writes under one issue take
 * turns, so a closing is seen or waits (S6B-AC-51), and the limits are counted under that lock
 * (S6B-AC-09). Task work leaves the issue's history and version alone (S6B-AC-31) and is not audited.
 */
@Service
public class TaskService {

    static final int TITLE_MAX = 100;
    static final int CONTENT_MAX = 4_000;

    /** Tasks of an issue that are not deleted, and with the deleted ones (S6B-AC-08). */
    static final int TASKS_MAX = 30;
    static final int TASKS_WITH_DELETED_MAX = 100;

    /** Notes of a task that are not deleted, and entries with the deleted notes and the changes (S6B-AC-23). */
    static final int NOTES_MAX = 100;
    static final int ENTRIES_MAX = 1_000;

    /** A task with its entries; {@code notes} is empty for a deleted task (S6B-AC-10). */
    public record TaskView(TaskRow task, boolean overdue, List<NoteRow> notes) {}

    /** The tasks of an issue and how many are open (S6B-AC-24). */
    public record IssueTasks(List<TaskView> items, long openCount) {}

    /** A row of the caller's own tasks and whether it is overdue (S6B-AC-60). */
    public record MyTask(MyTaskRow row, boolean overdue) {}

    private final TaskRepository tasks;
    private final IssueRepository issues;
    private final CompanyRepository companies;
    private final IssueNotifications notifications;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;
    private final InstallationZone zone;
    private final Clock clock;

    TaskService(TaskRepository tasks, IssueRepository issues, CompanyRepository companies,
            IssueNotifications notifications, AccountRateLimit rateLimit, Transactions transactions,
            InstallationZone zone, Clock clock) {
        this.tasks = tasks;
        this.issues = issues;
        this.companies = companies;
        this.notifications = notifications;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * Every task of an issue the viewer sees, in the order they were made, each with its notes and
     * changes oldest first, and how many are open (S6B-AC-24). Also on a {@code CLOSED} issue.
     */
    @Transactional(readOnly = true)
    public IssueTasks list(Viewer viewer, String key) {
        IssueRow issue = IssueKey.parse(key).flatMap(k -> issues.findByKey(viewer, k))
                .orElseThrow(ApiException::notFound);
        List<TaskRow> rows = tasks.listOfIssue(issue.id());
        Map<Long, List<NoteRow>> notes = tasks.notesOf(rows.stream().filter(t -> !t.deleted()).map(TaskRow::id)
                .toList());
        LocalDate today = zone.today(clock);
        List<TaskView> items = rows.stream().map(t -> view(t, notes.getOrDefault(t.id(), List.of()), today))
                .toList();
        long open = rows.stream().filter(t -> !t.deleted() && t.status() == Status.OPEN).count();
        return new IssueTasks(items, open);
    }

    /**
     * Staff add a task to an issue they see (S6B-AC-01): {@code OPEN}, with the assignee and the due date
     * when given. The title is 1 to 100 characters after trimming (S6B-AC-02); the assignee is an account
     * that can be the issue's assignee (S6B-AC-04); the due date is read like the issue's (S6B-AC-05).
     * Input is checked before the status of the issue, and the limits after it (S6B-AC-08). An assignee
     * other than the caller is told (S6B-AC-33). No entry is written (S6B-AC-30).
     */
    public TaskView create(Viewer viewer, String key, String title, Long assigneeId, String dueDate) {
        rateLimit.count(AccountRateLimit.Action.TASK, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            List<ApiError.FieldError> errors = new ArrayList<>();
            String text = TextInput.required("title", title, TITLE_MAX, errors);
            LocalDate date = dueDate == null ? null : dueDate(dueDate, errors);
            if (assigneeId != null) {
                assignee(viewer, issue, assigneeId, errors);
            }
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            Counts counts = tasks.countTasks(issue.id());
            if (counts.kept() >= TASKS_MAX || counts.all() >= TASKS_WITH_DELETED_MAX) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_LIMIT_EXCEEDED",
                        "This issue has as many tasks as it can have.");
            }
            long id = tasks.insert(issue.id(), text, assigneeId, date, viewer.accountId(), now());
            if (assigneeId != null) {
                notifications.taskAssigned(viewer, issue, id, assigneeId);
            }
            return view(tasks.find(issue.id(), id).orElseThrow());
        });
    }

    /**
     * Staff change the fields of a task that are sent (S6B-AC-03): {@code null} clears the assignee and
     * the due date and is refused for the title and the status. The assignee is checked only when it
     * differs from the task's (S6B-AC-04); the due date whenever it is sent, also the date the task has
     * (S6B-AC-05). {@code DONE} records who and when, {@code OPEN} clears both (S6B-AC-06). Each changed
     * field leaves a {@code CHANGE} entry (S6B-AC-30); a task with 1,000 entries takes no edit that would
     * leave one (S6B-AC-23). A new assignee other than the caller is told (S6B-AC-33). The later request
     * wins; the task has no version.
     *
     * @param id the task's ID as the path had it; one that is no number is an ID no task has
     */
    public TaskView update(Viewer viewer, String key, String id, JsonNode title, JsonNode status,
            JsonNode assigneeId, JsonNode dueDate) {
        rateLimit.count(AccountRateLimit.Action.TASK, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long taskId = id(id);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            TaskRow task = tasks.find(issue.id(), taskId).orElseThrow(ApiException::notFound);
            List<ApiError.FieldError> errors = new ArrayList<>();
            String newTitle = title == null ? null : title(title, errors);
            Status newStatus = status == null ? null : status(status, errors);
            Long newAssignee = assigneeId == null ? null : accountId(assigneeId, errors);
            if (newAssignee != null && !newAssignee.equals(task.assigneeId())) {
                assignee(viewer, issue, newAssignee, errors);
            }
            LocalDate newDueDate = dueDate == null || dueDate.isNull() ? null : dueDate(dueDate, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (task.deleted()) {
                throw taskDeleted();
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            List<Change> changes = TaskChanges.of(
                    new TaskChanges.Task(task.title(), task.status(), task.assigneeId(), task.dueDate()),
                    new TaskChanges.Edit(newTitle, newStatus, assigneeId != null, newAssignee, dueDate != null,
                            newDueDate));
            if (changes.isEmpty()) {
                return view(task);
            }
            if (tasks.countNotes(taskId).all() >= ENTRIES_MAX) {
                throw entryLimit();
            }
            Instant now = now();
            Status to = newStatus == null ? task.status() : newStatus;
            boolean done = to == Status.DONE;
            boolean becameDone = done && task.status() != Status.DONE;
            tasks.update(taskId, newTitle == null ? task.title() : newTitle, to,
                    assigneeId == null ? task.assigneeId() : newAssignee,
                    dueDate == null ? task.dueDate() : newDueDate,
                    !done ? null : becameDone ? Long.valueOf(viewer.accountId()) : accountId(task.doneBy()),
                    !done ? null : becameDone ? now : task.doneAt());
            for (Change change : changes) {
                tasks.insertChange(taskId, viewer.accountId(), change.field().name(), change.oldValue(),
                        change.newValue(), now);
            }
            if (changes.stream().anyMatch(c -> c.field() == TaskChanges.Field.ASSIGNEE) && newAssignee != null) {
                notifications.taskAssigned(viewer, issue, taskId, newAssignee);
            }
            return view(tasks.find(issue.id(), taskId).orElseThrow());
        });
    }

    /**
     * The task's creator or an admin deletes it (S6B-AC-10, S6B-AC-11), on a {@code CLOSED} issue too:
     * the task becomes its trace, its notes lose their content, its changes their values, and the inbox
     * items about it go (S6B-AC-36). A deleted task stays as it is. Not counted toward the rate limit
     * (S6B-AC-13).
     *
     * @param id the task's ID as the path had it; one that is no number is an ID no task has
     */
    public void delete(Viewer viewer, String key, String id) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long taskId = id(id);
        transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            TaskRow task = tasks.find(issue.id(), taskId).orElseThrow(ApiException::notFound);
            if (!viewer.admin() && task.createdBy().accountId() != viewer.accountId()) {
                throw ApiException.forbidden();
            }
            if (tasks.updateDeleted(taskId, viewer.accountId(), now()) > 0) {
                notifications.taskDeleted(taskId);
            }
        });
    }

    /**
     * Staff add a note of 1 to 4,000 characters to a task (S6B-AC-20). A deleted task takes none
     * (S6B-AC-12); a task takes at most 100 notes that are not deleted and 1,000 entries in all
     * (S6B-AC-23). Nobody is told (S6B-AC-35).
     *
     * @param id the task's ID as the path had it
     */
    public NoteRow addNote(Viewer viewer, String key, String id, String content) {
        rateLimit.count(AccountRateLimit.Action.TASK, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long taskId = id(id);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            TaskRow task = tasks.find(issue.id(), taskId).orElseThrow(ApiException::notFound);
            List<ApiError.FieldError> errors = new ArrayList<>();
            String text = TextInput.multiLine("content", content, true, CONTENT_MAX, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (task.deleted()) {
                throw taskDeleted();
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            Counts counts = tasks.countNotes(taskId);
            if (counts.kept() >= NOTES_MAX || counts.all() >= ENTRIES_MAX) {
                throw entryLimit();
            }
            long noteId = tasks.insertNote(taskId, viewer.accountId(), text, now());
            return tasks.findNote(taskId, noteId).orElseThrow();
        });
    }

    /**
     * The author changes the content of a note (S6B-AC-21); nobody else, an admin neither, and a
     * {@code CHANGE} entry not at all (S6B-AC-32). A deleted task is seen before a deleted note
     * (S6B-AC-22). The content the note has already changes nothing.
     *
     * @param id the task's ID as the path had it
     * @param noteId the note's ID as the path had it
     */
    public NoteRow editNote(Viewer viewer, String key, String id, String noteId, String content) {
        rateLimit.count(AccountRateLimit.Action.TASK, viewer.accountId());
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long taskId = id(id);
        long entryId = id(noteId);
        return transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            TaskRow task = tasks.find(issue.id(), taskId).orElseThrow(ApiException::notFound);
            NoteRow note = tasks.findNote(taskId, entryId).orElseThrow(ApiException::notFound);
            if (note.kind() != Kind.NOTE || note.author().accountId() != viewer.accountId()) {
                throw ApiException.forbidden();
            }
            List<ApiError.FieldError> errors = new ArrayList<>();
            String text = TextInput.multiLine("content", content, true, CONTENT_MAX, errors);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (task.deleted()) {
                throw taskDeleted();
            }
            if (note.deleted()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_NOTE_DELETED", "This note is deleted.");
            }
            if (issue.status() == IssueStatus.CLOSED) {
                throw issueClosed();
            }
            if (text.equals(note.content())) {
                return note;
            }
            tasks.updateNoteContent(entryId, text, now());
            return tasks.findNote(taskId, entryId).orElseThrow();
        });
    }

    /**
     * The author or an admin deletes a note (S6B-AC-22), on a {@code CLOSED} issue too; a {@code CHANGE}
     * entry is not deleted (S6B-AC-32). A deleted note stays as it is. Not counted toward the rate limit.
     *
     * @param id the task's ID as the path had it
     * @param noteId the note's ID as the path had it
     */
    public void deleteNote(Viewer viewer, String key, String id, String noteId) {
        IssueKey issueKey = IssueKey.parse(key).orElseThrow(ApiException::notFound);
        long taskId = id(id);
        long entryId = id(noteId);
        transactions.run(() -> {
            IssueRow issue = issues.lockByKey(viewer, issueKey).orElseThrow(ApiException::notFound);
            tasks.find(issue.id(), taskId).orElseThrow(ApiException::notFound);
            NoteRow note = tasks.findNote(taskId, entryId).orElseThrow(ApiException::notFound);
            if (note.kind() != Kind.NOTE || !viewer.admin() && note.author().accountId() != viewer.accountId()) {
                throw ApiException.forbidden();
            }
            tasks.updateNoteDeleted(entryId, viewer.accountId(), now());
        });
    }

    /**
     * One page of the viewer's own tasks with this status (TASK-7, S6B-AC-60 to 62): of issues the viewer
     * sees now and that are not {@code CLOSED}, not deleted.
     *
     * @param status the values of {@code status} as the request had them; none is {@code OPEN}. Anything
     *     but one value, {@code OPEN} or {@code DONE} with its case, is 400 {@code UNKNOWN_VALUE}
     */
    @Transactional(readOnly = true)
    public Paged<MyTask> listMine(Viewer viewer, String[] status, int page, int size) {
        Status wanted = Status.OPEN;
        if (status != null && status.length > 0) {
            if (status.length > 1 || !status[0].equals("OPEN") && !status[0].equals("DONE")) {
                throw ApiException.invalid("status", "UNKNOWN_VALUE");
            }
            wanted = Status.valueOf(status[0]);
        }
        PageRequest request = PageRequest.of(page, size);
        LocalDate today = zone.today(clock);
        return request.map(tasks.listMine(viewer, wanted, request.offset(), request.size()),
                tasks.countMine(viewer, wanted),
                row -> new MyTask(row, row.status() == Status.OPEN && DueDates.passed(row.dueDate(), today)));
    }

    /**
     * How many open tasks each of these issues has, which the caller read in the viewer's scope; an issue
     * without one is not in the map (S6B-AC-70). One read for a whole page.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> openCounts(Collection<UUID> issueIds) {
        return tasks.openCounts(issueIds);
    }

    private TaskView view(TaskRow task) {
        return view(task, task.deleted() ? List.of()
                : tasks.notesOf(List.of(task.id())).getOrDefault(task.id(), List.of()), zone.today(clock));
    }

    /** {@code overdue} is true only for an {@code OPEN} task whose due date has passed (S6B-AC-07). */
    private static TaskView view(TaskRow task, List<NoteRow> notes, LocalDate today) {
        return new TaskView(task, task.status() == Status.OPEN && !task.deleted()
                && DueDates.passed(task.dueDate(), today), task.deleted() ? List.of() : notes);
    }

    /**
     * Adds {@code UNKNOWN_VALUE} for {@code assigneeId} unless the account can be the assignee of the
     * issue: an {@code ACTIVE} admin, or an {@code ACTIVE} agent assigned to its company (TASK-6), one
     * answer whatever the reason (S6B-AC-04). Read under the issue row lock, without locking the account.
     */
    private void assignee(Viewer viewer, IssueRow issue, long accountId, List<ApiError.FieldError> errors) {
        if (companies.findAssignable(viewer, issue.companyId(), accountId).isEmpty()) {
            errors.add(new ApiError.FieldError("assigneeId", "UNKNOWN_VALUE"));
        }
    }

    /** The title of an edit: JSON {@code null} is {@code REQUIRED}, anything but text {@code INVALID_FORMAT}. */
    private static String title(JsonNode sent, List<ApiError.FieldError> errors) {
        if (!sent.isNull() && !sent.isString()) {
            errors.add(new ApiError.FieldError("title", "INVALID_FORMAT"));
            return null;
        }
        return TextInput.required("title", sent.isNull() ? null : sent.asString(), TITLE_MAX, errors);
    }

    /** The status of an edit: JSON {@code null} is {@code REQUIRED}, anything but the two names {@code UNKNOWN_VALUE}. */
    private static Status status(JsonNode sent, List<ApiError.FieldError> errors) {
        if (sent.isNull()) {
            errors.add(new ApiError.FieldError("status", "REQUIRED"));
            return null;
        }
        String name = sent.isString() ? sent.asString() : "";
        if (!name.equals("OPEN") && !name.equals("DONE")) {
            errors.add(new ApiError.FieldError("status", "UNKNOWN_VALUE"));
            return null;
        }
        return Status.valueOf(name);
    }

    /** The assignee of an edit: JSON {@code null} is nobody (null), anything but a whole number {@code INVALID_FORMAT}. */
    private static Long accountId(JsonNode sent, List<ApiError.FieldError> errors) {
        if (sent.isNull()) {
            return null;
        }
        if (!sent.isIntegralNumber() || !sent.canConvertToLong()) {
            errors.add(new ApiError.FieldError("assigneeId", "INVALID_FORMAT"));
            return null;
        }
        return sent.asLong();
    }

    /** The due date of an edit, not {@code null}: text by {@link DueDates#read}, anything else {@code INVALID_FORMAT}. */
    private LocalDate dueDate(JsonNode sent, List<ApiError.FieldError> errors) {
        if (!sent.isString()) {
            errors.add(new ApiError.FieldError("dueDate", "INVALID_FORMAT"));
            return null;
        }
        return dueDate(sent.asString(), errors);
    }

    private LocalDate dueDate(String sent, List<ApiError.FieldError> errors) {
        DueDates.Read read = DueDates.read(sent, zone.today(clock));
        if (read.error() != null) {
            errors.add(new ApiError.FieldError("dueDate", read.error()));
        }
        return read.date();
    }

    private static Long accountId(TaskRepository.Person person) {
        return person == null ? null : person.accountId();
    }

    /** A task or note ID written in the path: a positive number that fits a {@code bigint}; else 404. */
    private static long id(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
            throw ApiException.notFound();
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ApiException.notFound();
        }
    }

    private static ApiException issueClosed() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ISSUE_CLOSED", "This issue is closed.");
    }

    private static ApiException taskDeleted() {
        return new ApiException(HttpStatus.BAD_REQUEST, "TASK_DELETED", "This task is deleted.");
    }

    private static ApiException entryLimit() {
        return new ApiException(HttpStatus.BAD_REQUEST, "TASK_NOTE_LIMIT_EXCEEDED",
                "This task has as many notes as it can have.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
