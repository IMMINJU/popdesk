package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;

/**
 * The business hours of one weekday (SLA-2): one span within the day, on the wall clock of the installation
 * time zone (ADR D-36). The same shape in {@link SlaPolicy} and in {@link ChangeSlaPolicyRequest}.
 */
public record SlaHours(
        DayOfWeek weekday,
        @Schema(description = "`HH:MM`, from `00:00` to `23:59`, in whole minutes. Null, together with `closes`, "
                + "on a day without business hours.", nullable = true) String opens,
        @Schema(description = "`HH:MM`, later than `opens`; `24:00` is the end of the day. A span never goes past "
                + "midnight. Null, together with `opens`, on a day without business hours.", nullable = true)
                String closes) {}
