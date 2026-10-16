package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One SLA goal of a company's issues over the chosen days (S8B-AC-23, S8B-AC-24). It counts the company's
 * issues under SLA, not in the trash, whose goal stopped for good as `MET` or `MISSED` within the last
 * `days` × 24 hours. A resolution goal that a reopen took back counts once, when it stops again.
 */
public record SlaGoalStat(
        @Schema(description = "The lower median of the business time the counted issues took, in minutes "
                + "rounded down: of an even number of values, the smaller of the middle two. For first response "
                + "only issues stopped by a response count here; issues resolved or closed without one count "
                + "only in `met` and `missed`. Null when there is nothing to take the median of (S8B-AC-24).",
                nullable = true) Long medianMinutes,
        @Schema(description = "Issues whose goal stopped as `MET`.") long met,
        @Schema(description = "Issues whose goal stopped as `MISSED`. Issues without a goal (`NONE`) are in "
                + "neither count.") long missed) {}
