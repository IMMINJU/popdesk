package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.sla.BusinessCalendar.Span;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * One version of the SLA policy, which never changes once made (design.md 1절).
 *
 * @param number the version number
 * @param zone the time zone of the version; for version 1 before the first run of {@code sla-recalculate}, the
 *     installation's
 * @param startsAt when popdesk began to count with this version
 * @param changedBy the admin who made it, or null for popdesk
 * @param changedAt when it was made
 * @param targets the goals of each of the four priorities
 * @param hours the business hours of each weekday that has them; empty for 24 hours every day
 * @param holidays earliest first
 */
public record SlaVersion(int number, ZoneId zone, Instant startsAt, Changer changedBy, Instant changedAt,
        Map<Priority, Goals> targets, Map<DayOfWeek, Span> hours, List<LocalDate> holidays) {

    /** The goals of one priority in business minutes, each null when there is none. */
    public record Goals(Integer firstResponse, Integer resolution) {

        static final Goals NONE = new Goals(null, null);
    }

    /** The admin who made a version; {@code photoUpdatedAt} is null without a profile photo. */
    public record Changer(long accountId, String name, Role role, Instant photoUpdatedAt) {}

    /** The goals of a priority; both null when the version has none for it. */
    public Goals goals(Priority priority) {
        return targets.getOrDefault(priority, Goals.NONE);
    }

    /** Whether SLA is on with this version: at least one goal is set (acceptance.md 2절). */
    public boolean on() {
        return targets.values().stream().anyMatch(g -> g.firstResponse() != null || g.resolution() != null);
    }

    /** The business time of this version. */
    public BusinessCalendar calendar() {
        return new BusinessCalendar(zone, hours, holidays);
    }

    /** Whether the goals, the business hours, and the holidays are those of {@code other}. */
    boolean sameRules(SlaVersion other) {
        return targets.equals(other.targets) && hours.equals(other.hours) && holidays.equals(other.holidays);
    }
}
