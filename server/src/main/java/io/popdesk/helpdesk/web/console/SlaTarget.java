package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The SLA goals of one priority (SLA-1), in business minutes. The same shape in {@link SlaPolicy} and in
 * {@link ChangeSlaPolicyRequest}.
 */
public record SlaTarget(
        Priority priority,
        @Schema(description = "Business minutes from filing to the first public comment of staff, 1 to 525,600 "
                + "(365 days of 24 hours); null without this goal (S8A-AC-01).", nullable = true)
                Integer firstResponseMinutes,
        @Schema(description = "Business minutes from filing to `RESOLVED` or `CLOSED`, 1 to 525,600; not shorter "
                + "than `firstResponseMinutes` when both are set. Null without this goal (S8A-AC-01, "
                + "S8A-AC-04).", nullable = true) Integer resolutionMinutes) {}
