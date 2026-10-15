package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A row of the caller's own tasks (TASK-7, S6B-AC-60). */
public record MyTask(
        long id,
        String title,
        TaskStatus status,
        @Schema(description = "`YYYY-MM-DD`; null without a due date.", nullable = true) LocalDate dueDate,
        @Schema(description = "The task is `OPEN` and its due date is before today in the installation time "
                + "zone (S6B-AC-07).") boolean overdue,
        @Schema(description = "When the task became `DONE`; null while it is `OPEN`.", nullable = true)
                OffsetDateTime doneAt,
        TaskIssue issue,
        String companyCode,
        String companyName) {}
