package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The due date is read as text so that a value that is not a date is the field error of the contract. */
public record CreateTaskRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 100 characters after the "
                + "whitespace around it is removed (S6B-AC-02).") String title,
        @Schema(description = "An `ACTIVE` admin, or an `ACTIVE` agent assigned to the issue's company, as for "
                + "the issue's assignee (TASK-6, S6B-AC-04). Left out or `null`: nobody.", nullable = true)
                @Nullable Long assigneeId,
        @Schema(type = "string", format = "date", description = "`YYYY-MM-DD`, today or later in the "
                + "installation time zone, by the rules of the issue's due date (S6B-AC-05). Left out or "
                + "`null`: none.", nullable = true) @Nullable String dueDate) {}
