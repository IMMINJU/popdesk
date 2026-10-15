package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A task of an issue (TASK-2), with its notes and changes. A deleted task stays in the list as a trace
 * (COM-7, S6B-AC-10): its title is {@code ""}, its assignee and due date {@code null}, {@code overdue}
 * {@code false}, and {@code notes} empty.
 */
public record Task(
        @Schema(description = "Tasks of an issue are in the order of their IDs, the order they were made "
                + "(S6B-AC-24).") long id,
        @Schema(description = "1 to 100 characters. An empty string once the task is deleted (S6B-AC-10).")
                String title,
        TaskStatus status,
        @Schema(description = "Null when nobody is assigned, and once the task is deleted.", nullable = true)
                Person assignee,
        @Schema(description = "`YYYY-MM-DD`; null without a due date, and once the task is deleted (TASK-6).",
                nullable = true) LocalDate dueDate,
        @Schema(description = "The task is `OPEN` and its due date is before today in the installation time "
                + "zone (S6B-AC-07). `false` for a `DONE` task, without a due date, and once the task is deleted.")
                boolean overdue,
        Person createdBy,
        OffsetDateTime createdAt,
        @Schema(description = "Who made the task `DONE`; null while it is `OPEN` (S6B-AC-06).", nullable = true)
                Person doneBy,
        @Schema(description = "When the task became `DONE`; null while it is `OPEN` (S6B-AC-06).", nullable = true)
                OffsetDateTime doneAt,
        boolean deleted,
        @Schema(description = "The creator, or an admin (TASK-2).", nullable = true) Person deletedBy,
        @Schema(nullable = true) OffsetDateTime deletedAt,
        @Schema(description = "Notes and changes, oldest first (S6B-AC-24). Empty once the task is deleted "
                + "(S6B-AC-10).") List<TaskNote> notes) {}
