package io.popdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.trash.TrashJobs;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps of slice 8a, through the API only: read and change the SLA policy, read the {@code sla} of an issue on the
 * console (detail and list), move the app clock to a wall-clock time of the installation, and wait for a run of the
 * job {@code sla-recalculate}. Every step expects its one status and fails with an {@link AssertionError}
 * otherwise, so before the slice is built a test fails at the step.
 *
 * <p>Public, with public methods, so that the clock tests of server tab 2 and the hidden E2E checks can use it.
 */
public final class SlaSteps {

    /** {@code GET} and {@code PUT}, admins only (S8A-AC-01, S8A-AC-02). */
    public static final String POLICY = "/api/console/sla-policy";

    /** The cleanup job that works out due times again after a new version (S8A-AC-20, S8A-AC-25). */
    public static final String JOB = "sla-recalculate";

    public static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "URGENT");

    public static final String FIRST_RESPONSE = "firstResponse";
    public static final String RESOLUTION = "resolution";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Fixtures fixtures;
    private final TestClock clock;
    private final ZoneId zone;

    public SlaSteps(Fixtures fixtures, TestClock clock, ZoneId zone) {
        this.fixtures = fixtures;
        this.clock = clock;
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    // The policy

    /** {@code GET /api/console/sla-policy}, not sent yet. */
    public static ApiRequest policyRequest(Session session) {
        return session.client().get(POLICY);
    }

    /** The policy as it is now; expects 200. */
    public static JsonNode policy(Session admin) {
        return policyRequest(admin).expect(200).json();
    }

    /** {@code version} of a policy response. */
    public static int version(JsonNode policy) {
        JsonNode version = policy.path("version");
        if (!version.isIntegralNumber()) {
            throw new AssertionError("No version in the policy " + policy);
        }
        return version.asInt();
    }

    /** {@code PUT /api/console/sla-policy} with this body as it is, not sent yet. */
    public static ApiRequest changeRequest(Session session, Map<String, Object> body) {
        return session.client().put(POLICY).json(body);
    }

    /** Replaces the policy with {@code policy}, sending the version read just before; expects 200. */
    public static JsonNode change(Session admin, Policy policy) {
        return changeRequest(admin, policy.body(version(policy(admin)))).expect(200).json();
    }

    /** Sets a policy with no goal at all, so SLA is off for issues filed from now on. */
    public static JsonNode turnOff(Session admin) {
        return change(admin, Policy.off());
    }

    /**
     * A whole policy, written short: goals per priority, business hours per weekday, holidays. What is not set is
     * empty: no goal, no business hours on any day (so every day counts 24 hours), no holiday.
     */
    public static final class Policy {

        private final Map<String, Integer[]> targets = new LinkedHashMap<>();
        private final Map<DayOfWeek, String[]> hours = new EnumMap<>(DayOfWeek.class);
        private final TreeSet<LocalDate> holidays = new TreeSet<>();

        private Policy() {}

        /** No goal, no business hours, no holiday: SLA is off. */
        public static Policy off() {
            return new Policy();
        }

        /** The goals of one priority in business minutes; null is no goal. */
        public Policy target(String priority, Integer firstResponseMinutes, Integer resolutionMinutes) {
            targets.put(priority, new Integer[] {firstResponseMinutes, resolutionMinutes});
            return this;
        }

        /** The business hours of one weekday, {@code "HH:MM"}. */
        public Policy day(DayOfWeek day, String opens, String closes) {
            hours.put(day, new String[] {opens, closes});
            return this;
        }

        /** The same business hours Monday to Friday; Saturday and Sunday stay without. */
        public Policy weekdays(String opens, String closes) {
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                    day(day, opens, closes);
                }
            }
            return this;
        }

        public Policy holiday(LocalDate day) {
            holidays.add(day);
            return this;
        }

        /** The four goals, LOW first, as the API writes them. */
        public List<Map<String, Object>> targets() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String priority : PRIORITIES) {
                Integer[] goals = targets.getOrDefault(priority, new Integer[2]);
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("priority", priority);
                target.put("firstResponseMinutes", goals[0]);
                target.put("resolutionMinutes", goals[1]);
                out.add(target);
            }
            return out;
        }

        /** The seven weekdays, Monday first, as the API writes them. */
        public List<Map<String, Object>> hours() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (DayOfWeek day : DayOfWeek.values()) {
                String[] span = hours.getOrDefault(day, new String[2]);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("weekday", day.name());
                line.put("opens", span[0]);
                line.put("closes", span[1]);
                out.add(line);
            }
            return out;
        }

        /** The holidays, earliest first, as {@code "YYYY-MM-DD"}. */
        public List<String> holidays() {
            return holidays.stream().map(LocalDate::toString).toList();
        }

        /** The request body with this {@code version}. */
        public Map<String, Object> body(int version) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", version);
            body.put("targets", targets());
            body.put("hours", hours());
            body.put("holidays", holidays());
            return body;
        }
    }

    /** {@code value} (maps, lists, text, numbers) as JSON. */
    public static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    /** RFC 8785 text of a JSON value, to compare two values whatever their key order. */
    public static String canonical(JsonNode value) {
        return AuditChainMath.canonical(value);
    }

    // The sla of an issue

    /** {@code sla} of the issue as staff read it ({@code GET /api/console/issues/{key}}); a JSON null when not under SLA. */
    public static JsonNode sla(Session staff, String key) {
        return slaOf(staff.client().get("/api/console/issues/" + key).expect(200).json());
    }

    /** {@code sla} of an issue or list item. Fails when the field is missing. */
    public static JsonNode slaOf(JsonNode issue) {
        if (!issue.has("sla")) {
            throw new AssertionError("No field sla in " + issue);
        }
        return issue.get("sla");
    }

    /**
     * {@code sla} of the issue as an item of the staff list narrowed to its company ({@code companyCode}). Fails when
     * the first 100 items do not hold it.
     */
    public static JsonNode listedSla(Session staff, String companyCode, String key) {
        ApiResponse page = IssueFlow.list(staff, "companyCode", companyCode);
        for (JsonNode item : page.json().path("items")) {
            if (key.equals(item.path("key").asString(null))) {
                return slaOf(item);
            }
        }
        throw new AssertionError(page.request() + ": no item " + key + " in " + page.snippet());
    }

    /** One goal of an {@code sla}: {@link #FIRST_RESPONSE} or {@link #RESOLUTION}. Fails when {@code sla} is null. */
    public static JsonNode goal(JsonNode sla, String name) {
        if (sla == null || sla.isNull() || !sla.path(name).isObject()) {
            throw new AssertionError("No goal " + name + " in sla " + sla);
        }
        return sla.path(name);
    }

    /** {@code state} of a goal, or null. */
    public static String state(JsonNode goal) {
        return goal.path("state").isNull() ? null : goal.path("state").asString(null);
    }

    /** {@code dueAt} of a goal, or null. */
    public static Instant dueAt(JsonNode goal) {
        return IssueFlow.time(goal, "dueAt");
    }

    /** {@code remainingMinutes} of a goal, or null. */
    public static Long remainingMinutes(JsonNode goal) {
        JsonNode value = goal.path("remainingMinutes");
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No remainingMinutes in " + goal);
        }
        return value.asLong();
    }

    // The clock

    /** The app's time now as a wall-clock time of the installation. */
    public ZonedDateTime now() {
        return clock.instant().atZone(zone);
    }

    /** The installation's wall-clock time at {@code instant}. */
    public ZonedDateTime local(Instant instant) {
        return instant.atZone(zone);
    }

    /** The instant of a wall-clock time of the installation. */
    public Instant at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }

    /**
     * Moves the app clock forward to the next {@code day} at {@code time} in the installation's zone, at least a
     * minute from now and at most a week. Gives the new time. Sessions may have ended after the move; sign in again.
     */
    public Instant moveToNext(DayOfWeek day, LocalTime time) {
        ZonedDateTime now = now();
        ZonedDateTime target = now.with(time);
        while (target.getDayOfWeek() != day || target.isBefore(now.plusMinutes(1))) {
            target = target.plusDays(1).with(time);
        }
        return clock.advance(Duration.between(now.toInstant(), target.toInstant()));
    }

    /** Moves the app clock forward by whole minutes and gives the new time. */
    public Instant advanceMinutes(long minutes) {
        return clock.advance(Duration.ofMinutes(minutes));
    }

    // The job

    /**
     * Waits until {@code sla-recalculate} ran at or after {@code since}, as the system status shows it (C-18), and
     * gives its status line. {@code admin} must be signed in at the app's time now.
     */
    public static JsonNode awaitRecalculation(Session admin, Instant since) {
        return TrashJobs.awaitRun(admin, JOB, since);
    }

    /** As {@link #awaitRecalculation(Session, Instant)}, for a run that worked out at least {@code atLeast} issues. */
    public static JsonNode awaitRecalculation(Session admin, Instant since, long atLeast) {
        return TrashJobs.awaitRun(admin, JOB, since, atLeast);
    }

    /**
     * Moves the clock one minute on, the job's period, and waits for a run of {@code sla-recalculate} at that time;
     * gives the time. A run found with {@code since} equal to a time the clock already had may be one from before a
     * change, so wait this way after a change.
     */
    public Instant recalculateNow(Session admin) {
        Instant at = clock.advance(Duration.ofMinutes(1));
        awaitRecalculation(admin, at);
        return at;
    }

    /**
     * Moves the app clock forward to {@code instant} and gives it. Fails when the clock is already past it: the
     * context's clock is shared, and another test of the context may have moved it.
     */
    public Instant advanceTo(Instant instant) {
        Duration ahead = Duration.between(clock.instant(), instant);
        if (ahead.isNegative()) {
            throw new AssertionError("The app clock is at " + clock.instant() + ", past " + instant);
        }
        return clock.advance(ahead);
    }

    // Checking a goal

    /** {@code createdAt} of a filed issue. */
    public static Instant createdAt(Issue issue) {
        return IssueFlow.time(issue.json(), "createdAt");
    }

    /** Fails unless the goal has this {@code state}, {@code dueAt} (to the second), and {@code remainingMinutes}. */
    public static void assertGoal(JsonNode goal, String state, Instant dueAt, Long remaining, String what) {
        assertThat(state(goal)).as("state of %s: %s", what, goal).isEqualTo(state);
        assertThat(dueAt(goal)).as("dueAt of %s: %s", what, goal)
                .isEqualTo(dueAt == null ? null : dueAt.truncatedTo(ChronoUnit.SECONDS));
        assertThat(remainingMinutes(goal)).as("remainingMinutes of %s: %s", what, goal).isEqualTo(remaining);
    }

    // People

    /** The first admin, signed in now; use after moving the clock far. */
    public Session admin() {
        return fixtures.signInAdmin();
    }

    /** An admin, a new company, a customer of it, and an agent assigned to it. */
    public record Desk(Session admin, String code, Session customer, Session agent) {}

    /** A new {@link Desk}, with the first admin signed in now. */
    public Desk desk() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        return new Desk(admin, code, fixtures.customer(admin, code), fixtures.agent(admin, code));
    }

    /** The same people signed in again; use after moving the clock far. */
    public Desk signInAgain(Desk desk) {
        return new Desk(fixtures.signInAgain(desk.admin()), desk.code(), fixtures.signInAgain(desk.customer()),
                fixtures.signInAgain(desk.agent()));
    }

    /** A new issue of the desk's customer with this priority. */
    public Issue file(Desk desk, String priority) {
        return fixtures.fileIssue(desk.customer(), NewIssue.in(desk.code()).priority(priority));
    }
}
