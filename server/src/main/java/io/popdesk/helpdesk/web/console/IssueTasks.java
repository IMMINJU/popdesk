package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Every task of one issue, at most 100 with the deleted ones (S6B-AC-08, S6B-AC-24). */
public record IssueTasks(
        @Schema(description = "In the order they were made, deleted ones as traces in their place.")
                List<Task> items,
        @Schema(description = "Tasks that are `OPEN` and not deleted, also on a `CLOSED` issue.") long openCount) {}
