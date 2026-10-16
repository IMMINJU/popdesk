package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What working SLA due times out again changed, for a {@code SLA_RECALCULATED} item (S8B-AC-14). Each count
 * is of changes to an issue, judged by the worse of its two running goals: an issue that became overdue and
 * then no longer overdue while the policy changed twice counts in both (S8B-AC-12).
 */
public record SlaSummary(
        @Schema(description = "Issues that became `BREACHED`; 0 or more.") long becameBreached,
        @Schema(description = "Issues that became `WARNING` from `OK` or from having no goal; 0 or more.")
                long becameWarning,
        @Schema(description = "Issues that moved back from `BREACHED` or `WARNING`, losing their goal included; "
                + "0 or more.") long released) {}
