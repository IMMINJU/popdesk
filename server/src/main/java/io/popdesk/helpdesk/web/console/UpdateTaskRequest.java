package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44, S6B-AC-03). The fields are read as JSON to tell {@code null}
 * from a field left out: {@code null} clears the assignee and the due date, and is refused for the title
 * and the status.
 */
public record UpdateTaskRequest(
        @Schema(type = "string", description = "1 to 100 characters after the whitespace around it is removed. "
                + "`null` is refused.") JsonNode title,
        @Schema(type = "string", allowableValues = {"OPEN", "DONE"}, description = "`DONE` records who and when; "
                + "`OPEN` clears both (S6B-AC-06). `null` is refused.") JsonNode status,
        @Schema(implementation = Long.class, description = "An `ACTIVE` admin, or an `ACTIVE` agent "
                + "assigned to the issue's company (TASK-6). Checked only when it differs from the current "
                + "assignee (S6B-AC-04). `null` clears it.", nullable = true) JsonNode assigneeId,
        @Schema(type = "string", format = "date", description = "`YYYY-MM-DD`, today or later in the "
                + "installation time zone (S6B-AC-05). `null` clears it.", nullable = true) JsonNode dueDate) {}
