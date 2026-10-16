package io.popdesk.helpdesk.sla;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.InstallationZone;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.sla.BusinessCalendar.Span;
import io.popdesk.helpdesk.sla.SlaVersion.Goals;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and changing the SLA policy (SLA-1, SLA-2, S8A-AC-01 to S8A-AC-06), for admins only. A change is a new
 * version, the current one plus 1, made under the current version's row {@code FOR UPDATE} (design.md 0절 5): of two
 * changes sent with the same version one is 409, and a write of clocks that holds the row {@code FOR KEY SHARE}
 * finishes before the change reads the time it starts at (0절 8).
 */
@Service
public class SlaPolicyService {

    /** The longest goal in business minutes: 365 days of 24 hours (acceptance.md 5절 6). */
    static final int GOAL_MAX = 525_600;

    /** The most holidays a version has (acceptance.md 5절 6). */
    static final int HOLIDAYS_MAX = 500;

    /** A time of business hours: {@code HH:MM} in whole minutes, {@code 00:00} to {@code 23:59}. */
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    /** {@code closes} also takes the end of the day. */
    private static final String END_OF_DAY = "24:00";

    /** The goals of one priority as sent; any of the three may be null. */
    public record TargetInput(Priority priority, Integer firstResponseMinutes, Integer resolutionMinutes) {}

    /** The business hours of one weekday as sent; any of the three may be null. */
    public record HoursInput(DayOfWeek weekday, String opens, String closes) {}

    /** A change as sent: every part may be null, each list item too. */
    public record Change(Integer version, List<TargetInput> targets, List<HoursInput> hours,
            List<LocalDate> holidays) {}

    /** A version as the API gives it (S8A-AC-01), with its time zone as text. */
    public record Policy(SlaVersion version, String timeZone) {}

    private final SlaPolicyRepository repository;
    private final SlaPolicies policies;
    private final AuditLog audit;
    private final InstallationZone zone;
    private final Transactions transactions;
    private final Clock clock;

    SlaPolicyService(SlaPolicyRepository repository, SlaPolicies policies, AuditLog audit, InstallationZone zone,
            Transactions transactions, Clock clock) {
        this.repository = repository;
        this.policies = policies;
        this.audit = audit;
        this.zone = zone;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The current version (S8A-AC-01); before the first run of the job, version 1 has the app's time zone. */
    @Transactional(readOnly = true)
    public Policy get(Viewer viewer) {
        adminOnly(viewer);
        return policy(policies.version(repository.current()));
    }

    /**
     * Replaces the goals, the business hours, and the holidays (S8A-AC-02 to S8A-AC-04, S8A-AC-06). Checks, in this
     * order: the role (403), every field of the body (400, all listed, design.md 5절), then under the current
     * version's row the version sent (409, also when the values are the same ones), then the values: the ones the
     * policy has already change nothing. Otherwise the next version starts now, with the app's time zone, and one
     * audit entry names what changed.
     */
    public Policy change(Viewer viewer, AuditLog.Actor admin, Change input) {
        adminOnly(viewer);
        Rules rules = check(input);
        return transactions.run(() -> {
            int current = repository.lockCurrent("update");
            if (input.version() != current) {
                throw ApiException.conflict("VERSION_CONFLICT", "Someone changed the SLA policy. Read it again.");
            }
            SlaVersion before = policies.version(current);
            if (before.targets().equals(rules.targets()) && before.hours().equals(rules.hours())
                    && before.holidays().equals(rules.holidays())) {
                return policy(before);
            }
            // The time is read under the lock, after every write of clocks that held the row (design.md 0절 8).
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            int next = current + 1;
            repository.insert(next, zone.zone(), now, admin.accountId(), now, rules.targets(), rules.hours(),
                    rules.holidays());
            Map<String, Object> changes = new LinkedHashMap<>();
            if (!before.targets().equals(rules.targets())) {
                changes.put("targets", fromTo(targets(before.targets()), targets(rules.targets())));
            }
            if (!before.hours().equals(rules.hours())) {
                changes.put("hours", fromTo(hours(before.hours()), hours(rules.hours())));
            }
            if (!before.holidays().equals(rules.holidays())) {
                changes.put("holidays", fromTo(holidays(before.holidays()), holidays(rules.holidays())));
            }
            audit.record(admin, AuditAction.SLA_POLICY_CHANGED, Map.of("type", "SLA_POLICY", "version", next),
                    changes);
            // Read back in this transaction only, not kept: the change is not committed yet.
            return policy(repository.find(next, zone.zone()).orElseThrow());
        });
    }

    /** The parts of a valid change, in the shapes of {@link SlaVersion}. */
    private record Rules(Map<Priority, Goals> targets, Map<DayOfWeek, Span> hours, List<LocalDate> holidays) {}

    /** Every field of a change, all wrong ones listed (S8A-AC-04, design.md 5절); else the rules it sets. */
    private static Rules check(Change input) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (input.version() == null) {
            errors.add(new ApiError.FieldError("version", "REQUIRED"));
        }
        Map<Priority, Goals> targets = targets(input.targets(), errors);
        Map<DayOfWeek, Span> hours = hours(input.hours(), errors);
        List<LocalDate> holidays = holidays(input.holidays(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return new Rules(targets, hours, holidays);
    }

    private static Map<Priority, Goals> targets(List<TargetInput> sent, List<ApiError.FieldError> errors) {
        if (sent == null) {
            errors.add(new ApiError.FieldError("targets", "REQUIRED"));
            return null;
        }
        Map<Priority, Goals> out = new EnumMap<>(Priority.class);
        boolean unknown = sent.size() != Priority.values().length;
        for (int i = 0; i < sent.size(); i++) {
            TargetInput target = sent.get(i);
            if (target == null || target.priority() == null || out.containsKey(target.priority())) {
                unknown = true;
                continue;
            }
            String field = "targets[" + i + "].";
            Integer first = target.firstResponseMinutes();
            Integer resolution = target.resolutionMinutes();
            boolean firstFits = goal(first, field + "firstResponseMinutes", errors);
            boolean resolutionFits = goal(resolution, field + "resolutionMinutes", errors);
            if (firstFits && resolutionFits && first != null && resolution != null && resolution < first) {
                errors.add(new ApiError.FieldError(field + "resolutionMinutes", "OUT_OF_RANGE"));
            }
            out.put(target.priority(), new Goals(first, resolution));
        }
        if (unknown) {
            errors.add(new ApiError.FieldError("targets", "UNKNOWN_VALUE"));
        }
        return out;
    }

    /** Whether a goal is none or within 1 to {@link #GOAL_MAX}; an entry in {@code errors} when not. */
    private static boolean goal(Integer minutes, String field, List<ApiError.FieldError> errors) {
        if (minutes != null && (minutes < 1 || minutes > GOAL_MAX)) {
            errors.add(new ApiError.FieldError(field, "OUT_OF_RANGE"));
            return false;
        }
        return true;
    }

    private static Map<DayOfWeek, Span> hours(List<HoursInput> sent, List<ApiError.FieldError> errors) {
        if (sent == null) {
            errors.add(new ApiError.FieldError("hours", "REQUIRED"));
            return null;
        }
        Map<DayOfWeek, Span> out = new EnumMap<>(DayOfWeek.class);
        Set<DayOfWeek> seen = new HashSet<>();
        boolean unknown = sent.size() != DayOfWeek.values().length;
        for (int i = 0; i < sent.size(); i++) {
            HoursInput day = sent.get(i);
            if (day == null || day.weekday() == null || !seen.add(day.weekday())) {
                unknown = true;
                continue;
            }
            String field = "hours[" + i + "].";
            if (day.opens() == null && day.closes() == null) {
                continue;
            }
            if (day.opens() == null || day.closes() == null) {
                errors.add(new ApiError.FieldError(field + (day.opens() == null ? "opens" : "closes"), "REQUIRED"));
                continue;
            }
            Integer opens = TIME.matcher(day.opens()).matches() ? Integer.valueOf(minutes(day.opens())) : null;
            Integer closes = END_OF_DAY.equals(day.closes()) ? Integer.valueOf(BusinessCalendar.DAY_MINUTES)
                    : TIME.matcher(day.closes()).matches() ? Integer.valueOf(minutes(day.closes())) : null;
            if (opens == null) {
                errors.add(new ApiError.FieldError(field + "opens", "INVALID_FORMAT"));
            }
            if (closes == null) {
                errors.add(new ApiError.FieldError(field + "closes", "INVALID_FORMAT"));
            }
            if (opens != null && closes != null) {
                if (closes <= opens) {
                    errors.add(new ApiError.FieldError(field + "closes", "OUT_OF_RANGE"));
                } else {
                    out.put(day.weekday(), new Span(opens, closes));
                }
            }
        }
        if (unknown) {
            errors.add(new ApiError.FieldError("hours", "UNKNOWN_VALUE"));
        }
        return out;
    }

    private static int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    private static List<LocalDate> holidays(List<LocalDate> sent, List<ApiError.FieldError> errors) {
        if (sent == null) {
            errors.add(new ApiError.FieldError("holidays", "REQUIRED"));
            return null;
        }
        if (sent.size() > HOLIDAYS_MAX) {
            errors.add(new ApiError.FieldError("holidays", "TOO_MANY"));
        }
        TreeSet<LocalDate> out = new TreeSet<>();
        for (int i = 0; i < sent.size(); i++) {
            LocalDate day = sent.get(i);
            if (day == null) {
                errors.add(new ApiError.FieldError("holidays[" + i + "]", "REQUIRED"));
            } else if (!out.add(day)) {
                errors.add(new ApiError.FieldError("holidays[" + i + "]", "UNKNOWN_VALUE"));
            }
        }
        return List.copyOf(out);
    }

    private Policy policy(SlaVersion version) {
        return new Policy(version, version.zone().getId());
    }

    private static Map<String, Object> fromTo(Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    /** The goals as the API writes them, {@code LOW} first (S8A-AC-06). */
    static List<Map<String, Object>> targets(Map<Priority, Goals> targets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Priority priority : Priority.values()) {
            Goals goals = targets.getOrDefault(priority, Goals.NONE);
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("priority", priority.name());
            target.put("firstResponseMinutes", goals.firstResponse());
            target.put("resolutionMinutes", goals.resolution());
            out.add(target);
        }
        return out;
    }

    /** The business hours as the API writes them, Monday first, {@code HH:MM}. */
    static List<Map<String, Object>> hours(Map<DayOfWeek, Span> hours) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            Span span = hours.get(day);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("weekday", day.name());
            line.put("opens", span == null ? null : time(span.opens()));
            line.put("closes", span == null ? null : time(span.closes()));
            out.add(line);
        }
        return out;
    }

    private static List<String> holidays(List<LocalDate> holidays) {
        return holidays.stream().map(LocalDate::toString).toList();
    }

    /** {@code HH:MM} of minutes of the day; {@code 24:00} for the end of the day. */
    public static String time(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static void adminOnly(Viewer viewer) {
        if (viewer.role() != Role.ADMIN) {
            throw ApiException.forbidden();
        }
    }
}
