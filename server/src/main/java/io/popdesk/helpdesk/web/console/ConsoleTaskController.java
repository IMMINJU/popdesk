package io.popdesk.helpdesk.web.console;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.task.TaskRepository;
import io.popdesk.helpdesk.task.TaskRepository.NoteRow;
import io.popdesk.helpdesk.task.TaskService;
import io.popdesk.helpdesk.task.TaskService.TaskView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tasks of an issue and their notes (TASK-1 to TASK-7), for staff only. The paths are under the issue,
 * so what the caller may see is decided by the issue (S6B-AC-40); only the caller's own tasks have a
 * path of their own. Customers never reach these paths: a portal host answers 404 and a console host
 * 401, as for every console path (S6B-AC-42).
 */
@Tag(name = "Tasks")
@RestController
@RequestMapping("/api/console")
class ConsoleTaskController {

    /** Query parameters of the caller's own tasks; any other name is 400 (S6B-AC-61). */
    private static final Set<String> MY_TASKS_PARAMETERS = Set.of("status", "page", "size");

    private final TaskService tasks;

    ConsoleTaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    static Task task(TaskView view) {
        TaskRepository.TaskRow row = view.task();
        return new Task(row.id(), row.title(), TaskStatus.valueOf(row.status().name()), person(row.assignee()),
                row.dueDate(), view.overdue(), person(row.createdBy()), ConsoleIssueController.utc(row.createdAt()),
                person(row.doneBy()), ConsoleIssueController.utc(row.doneAt()), row.deleted(),
                person(row.deletedBy()), ConsoleIssueController.utc(row.deletedAt()),
                view.notes().stream().map(ConsoleTaskController::note).toList());
    }

    /** An assignee change names people and gives no text values (S6B-AC-30); every other one the reverse. */
    static TaskNote note(NoteRow row) {
        boolean assignee = "ASSIGNEE".equals(row.field());
        return new TaskNote(row.id(), TaskNoteKind.valueOf(row.kind().name()), person(row.author()), row.content(),
                row.field(), assignee ? null : row.oldValue(), assignee ? null : row.newValue(),
                assignee ? person(row.oldPerson()) : null, assignee ? person(row.newPerson()) : null,
                ConsoleIssueController.utc(row.createdAt()), ConsoleIssueController.utc(row.editedAt()), row.deleted(),
                ConsoleIssueController.utc(row.deletedAt()), person(row.deletedBy()));
    }

    private static Person person(TaskRepository.Person person) {
        return person == null ? null : ConsoleIssueController.person(person.accountId(), person.name(),
                person.role(), person.photoUpdatedAt());
    }

    private static MyTask myTask(TaskService.MyTask mine) {
        TaskRepository.MyTaskRow row = mine.row();
        return new MyTask(row.id(), row.title(), TaskStatus.valueOf(row.status().name()), row.dueDate(),
                mine.overdue(), ConsoleIssueController.utc(row.doneAt()),
                new TaskIssue(row.issueKey(), row.issueTitle(), row.issueStatus()), row.companyCode(),
                row.companyName());
    }

    static final String TASK_NOT_FOUND = "`NOT_FOUND`: no such issue or task, the caller cannot see the issue "
            + "(S6B-AC-40), or the task belongs to another issue (S6B-AC-41).";
    static final String NOTE_NOT_FOUND = "`NOT_FOUND`: no such issue, task, or note, the caller cannot see the "
            + "issue (S6B-AC-40), or the task or the note belongs to another issue or task (S6B-AC-41).";
    static final String RATE_LIMITED = "`RATE_LIMITED`: more than 60 tasks and notes added or edited in 60 seconds, "
            + "counted together; deleting is not counted (S6B-AC-13, COM-8).";
    static final String CLOSED = "`ISSUE_CLOSED`: the issue is `CLOSED`; nothing changed (S6B-AC-50).";
    static final String TASK_DELETED = "`TASK_DELETED`: the task is deleted; nothing changed (S6B-AC-12).";
    static final String ENTRY_LIMIT = "`TASK_NOTE_LIMIT_EXCEEDED`: the task has 1,000 entries already, deleted "
            + "notes and changes included; nothing changed (S6B-AC-23).";

    @Operation(operationId = "listIssueTasks", summary = "Tasks of an issue",
            description = "Every task of the issue in the order they were made, each with its notes and "
                    + "changes oldest first, all at once (S6B-AC-24). A deleted task stays in its place as a trace "
                    + "(S6B-AC-10). Also on a `CLOSED` issue.")
    @ApiResponse(responseCode = "200", description = "The tasks and the number of open ones.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = ConsoleIssueController.NOT_VISIBLE)
    @GetMapping("/issues/{key}/tasks")
    IssueTasks listIssueTasks(@PathVariable String key) {
        TaskService.IssueTasks list = tasks.list(CurrentAccount.get().viewer(), key);
        return new IssueTasks(list.items().stream().map(ConsoleTaskController::task).toList(), list.openCount());
    }

    @Operation(operationId = "createTask", summary = "Add a task to an issue",
            description = "The task is `OPEN` (S6B-AC-01). An assignee other than the caller gets an inbox item "
                    + "`TASK_ASSIGNED` and an email (S6B-AC-33). An issue has at most 30 tasks that are not "
                    + "deleted, and 100 with the deleted ones (S6B-AC-08). The issue's `version` does not change "
                    + "and its history gets no entry (S6B-AC-31).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `title` missing (`REQUIRED`), only "
            + "whitespace (`BLANK`), or over 100 characters (`TOO_LONG`, S6B-AC-02); `assigneeId` not an account "
            + "that can be the assignee (`UNKNOWN_VALUE`, one answer whatever the reason, S6B-AC-04); `dueDate` "
            + "not a date `YYYY-MM-DD` (`INVALID_FORMAT`) or before today (`OUT_OF_RANGE`, S6B-AC-05); `fields` "
            + "lists all. " + CLOSED + " `TASK_LIMIT_EXCEEDED`: the issue has 30 tasks, or 100 with the deleted "
            + "ones, already (S6B-AC-08).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = ConsoleIssueController.NOT_VISIBLE)
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PostMapping("/issues/{key}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    Task createTask(@PathVariable String key, @RequestBody CreateTaskRequest request) {
        return task(tasks.create(CurrentAccount.get().viewer(), key, request.title(), request.assigneeId(),
                request.dueDate()));
    }

    @Operation(operationId = "updateTask", summary = "Change a task's title, status, assignee, or due date",
            description = "A field left out keeps its value; `null` clears the assignee and the due date "
                    + "(S6B-AC-03). Anyone of the staff who sees the issue may change any task. Each field that "
                    + "changed adds one `CHANGE` entry to the task's notes; a field sent with the value it has "
                    + "adds none (S6B-AC-30). A new assignee other than the caller gets an inbox item "
                    + "`TASK_ASSIGNED` and an email (S6B-AC-33). The task has no `version`: the later request "
                    + "wins. The issue's `version` does not change (S6B-AC-31).")
    @ApiResponse(responseCode = "200", description = "The task as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `title` only whitespace (`BLANK`), over "
            + "100 characters (`TOO_LONG`), or `null` (`REQUIRED`); `status` unknown (`UNKNOWN_VALUE`) or `null` "
            + "(`REQUIRED`); `assigneeId` different from the current assignee and not an account that can be "
            + "the assignee (`UNKNOWN_VALUE`, S6B-AC-04); `dueDate` not a date `YYYY-MM-DD` (`INVALID_FORMAT`) "
            + "or before today (`OUT_OF_RANGE`), also when it is the date the task has already (S6B-AC-05); "
            + "`fields` lists all. " + TASK_DELETED + " " + CLOSED + " " + ENTRY_LIMIT)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = TASK_NOT_FOUND)
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PatchMapping("/issues/{key}/tasks/{id}")
    Task updateTask(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id,
            @RequestBody UpdateTaskRequest request) {
        return task(tasks.update(CurrentAccount.get().viewer(), key, id, request.title(), request.status(),
                request.assigneeId(), request.dueDate()));
    }

    @Operation(operationId = "deleteTask", summary = "Delete a task",
            description = "The task's creator or an admin may delete, on a `CLOSED` issue too (S6B-AC-10, "
                    + "S6B-AC-50). The list keeps a trace: the title, assignee, and due date are cleared and "
                    + "`deleted`, `deletedAt`, and `deletedBy` are set (COM-7). The task's notes are deleted with "
                    + "it and its changes lose their values. Inbox items about the task are deleted (S6B-AC-36). "
                    + "Deleting a deleted task again is 204 too and changes nothing.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: an agent who did not make the task "
            + "(S6B-AC-11). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = TASK_NOT_FOUND)
    @DeleteMapping("/issues/{key}/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTask(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id) {
        tasks.delete(CurrentAccount.get().viewer(), key, id);
    }

    @Operation(operationId = "createTaskNote", summary = "Add a note to a task",
            description = "Anyone of the staff who sees the issue (S6B-AC-20). A task has at most 100 notes that "
                    + "are not deleted, and 1,000 entries with the deleted notes and the changes (S6B-AC-23). "
                    + "Nobody is told (S6B-AC-35).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` missing (`REQUIRED`), only "
            + "whitespace (`BLANK`), or over 4,000 characters (`TOO_LONG`, S6B-AC-20). " + TASK_DELETED + " "
            + CLOSED + " `TASK_NOTE_LIMIT_EXCEEDED`: the task has 100 notes that are not deleted, or 1,000 "
            + "entries, already (S6B-AC-23).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = TASK_NOT_FOUND)
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PostMapping("/issues/{key}/tasks/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    TaskNote createTaskNote(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id,
            @RequestBody TaskNoteRequest request) {
        return note(tasks.addNote(CurrentAccount.get().viewer(), key, id, request.content()));
    }

    @Operation(operationId = "updateTaskNote", summary = "Edit the caller's own task note",
            description = "Only the author edits, an admin neither (S6B-AC-21). A new `content` sets `editedAt`. "
                    + "A `CHANGE` entry cannot be edited (S6B-AC-32).")
    @ApiResponse(responseCode = "200", description = "The note as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `content` missing or `null` (`REQUIRED`), "
            + "only whitespace (`BLANK`), or over 4,000 characters (`TOO_LONG`). `TASK_NOTE_DELETED`: the note is "
            + "deleted; nothing changed (S6B-AC-22). " + TASK_DELETED + " " + CLOSED)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: the caller did not write the note, or it is a "
            + "`CHANGE` entry (S6B-AC-21, S6B-AC-32). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOTE_NOT_FOUND)
    @ApiResponse(responseCode = "429", description = RATE_LIMITED)
    @PatchMapping("/issues/{key}/tasks/{id}/notes/{noteId}")
    TaskNote updateTaskNote(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String noteId,
            @RequestBody TaskNoteRequest request) {
        return note(tasks.editNote(CurrentAccount.get().viewer(), key, id, noteId, request.content()));
    }

    @Operation(operationId = "deleteTaskNote", summary = "Delete a task note",
            description = "The author or an admin may delete, on a `CLOSED` issue too (S6B-AC-22, S6B-AC-50). The "
                    + "list keeps a trace: `content` is emptied and `deleted`, `deletedAt`, and `deletedBy` are set "
                    + "(COM-7). Deleting a deleted note again is 204 too and changes nothing. A `CHANGE` entry "
                    + "cannot be deleted (S6B-AC-32).")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: an agent who did not write the note, or a "
            + "`CHANGE` entry (S6B-AC-22, S6B-AC-32). `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOTE_NOT_FOUND)
    @DeleteMapping("/issues/{key}/tasks/{id}/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTaskNote(@PathVariable String key,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id,
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String noteId) {
        tasks.deleteNote(CurrentAccount.get().viewer(), key, id, noteId);
    }

    @Operation(operationId = "listMyTasks", summary = "The caller's own tasks",
            description = "Tasks whose assignee is the caller, across every issue the caller can see now. Tasks "
                    + "of `CLOSED` issues, of issues of a company the caller is no longer assigned to, and deleted "
                    + "tasks are left out (S6B-AC-60, S6B-AC-62). `OPEN`: earliest due date first, tasks without "
                    + "one last, then in the order they were made. `DONE`: the most recently done first "
                    + "(S6B-AC-61). Pages neither repeat nor miss a task.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), `status` neither `OPEN` nor `DONE` (`UNKNOWN_VALUE`), or a parameter this list "
            + "does not take (`UNKNOWN_PARAMETER`, S6B-AC-61).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping("/tasks")
    Paged<MyTask> listMyTasks(
            @Parameter(description = "`OPEN` (the default) or `DONE`.", schema = @Schema(type = "string",
                    allowableValues = {"OPEN", "DONE"}, defaultValue = "OPEN"))
                    @RequestParam(required = false) @Nullable String status,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        List<ApiError.FieldError> unknown = request.getParameterMap().keySet().stream()
                .filter(name -> !MY_TASKS_PARAMETERS.contains(name)).sorted()
                .map(name -> new ApiError.FieldError(name, "UNKNOWN_PARAMETER")).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.invalid(unknown);
        }
        // Each value as sent: Spring joins a parameter given twice into one text with a comma.
        return tasks.listMine(CurrentAccount.get().viewer(), request.getParameterValues("status"), page, size)
                .map(ConsoleTaskController::myTask);
    }
}
