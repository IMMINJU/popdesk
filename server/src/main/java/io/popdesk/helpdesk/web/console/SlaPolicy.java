package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The SLA policy of the installation, one for all companies (SLA-1, SLA-2). SLA is on while at least one
 * goal is set; an installation that never set one is at version 1 with every list empty or every goal null
 * (S8A-AC-01).
 */
public record SlaPolicy(
        @Schema(description = "Goes up by 1 when the goals, the business hours, the holidays, or the time zone "
                + "change (SLA-1). Send it back with `updateSlaPolicy`.") int version,
        @Schema(description = "Four items, one for each priority, from `LOW` to `URGENT`.") List<SlaTarget> targets,
        @Schema(description = "Seven items, one for each weekday, from `MONDAY` to `SUNDAY`. When no day has "
                + "business hours, every day counts all 24 hours; otherwise a day without them counts none "
                + "(S8A-AC-12).") List<SlaHours> hours,
        @Schema(description = "Days that count no business time, even when every day counts 24 hours: "
                + "`YYYY-MM-DD` in the installation time zone, earliest first, at most 500 (S8A-AC-12).")
                List<LocalDate> holidays,
        @Schema(description = "The time zone the business hours and holidays are read in: the installation's "
                + "`POPDESK_TIMEZONE`, for example `Asia/Seoul`. Set by popdesk, not by this endpoint; when "
                + "the setting changes, popdesk makes a new version within a minute (S8A-AC-07).") String timeZone,
        @Schema(description = "When this version was made.") OffsetDateTime changedAt,
        @Schema(description = "The admin who made this version; null when popdesk made it: version 1, and a "
                + "version made because the time zone changed (S8A-AC-07).", nullable = true) Person changedBy) {}
