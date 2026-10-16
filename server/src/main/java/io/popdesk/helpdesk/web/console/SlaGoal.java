package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** One SLA goal of an issue, judged by the server when it answers (S8A-AC-18, S8A-AC-30). */
public record SlaGoal(
        @Schema(description = "`MET` or `MISSED` once the clock stopped for good; otherwise `BREACHED` after "
                + "`dueAt`, `WARNING` from 80% of the goal on, `OK` before that. At `dueAt` itself the goal is "
                + "not breached yet. Null when the issue's priority has no such goal (S8A-AC-10).",
                nullable = true) SlaGoalState state,
        @Schema(description = "When the goal runs out, to the second. Still given after `MET` or `MISSED`, as it "
                + "was when the clock stopped. Null without a goal.",
                nullable = true) OffsetDateTime dueAt,
        @Schema(description = "Business minutes left while the clock runs, rounded down; after `dueAt` a negative "
                + "number of business minutes past it, rounded up (S8A-AC-19). Null while the clock is "
                + "stopped and without a goal.", nullable = true) Long remainingMinutes) {}
