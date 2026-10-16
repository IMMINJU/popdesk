package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaStatsSteps.goal;
import static io.popdesk.sla.SlaStatsSteps.itemOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps;
import io.popdesk.sla.SlaStatsSteps.GoalStat;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.stats.StatsSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The SLA of the statistics (S8B-AC-21 to S8B-AC-25): per company, the goals settled in the last {@code days} times
 * 24 hours, counted as met and missed, and the lower median of their business time in whole minutes. Every day counts
 * 24 hours here, so the business time of a goal is the time from filing to settling (minus the time in the trash).
 * Each test makes its own companies and reads their items; the policy is set at the start of each test.
 */
class SlaStatsTest extends SlaAppTest {

    /** First response 60 minutes, resolution 120 for NORMAL; LOW has no goal, so its goals settle as NONE. */
    private static final Policy GOALS = Policy.off().target("NORMAL", 60, 120);

    private static final Set<String> GOAL_NAMES = new TreeSet<>(List.of(FIRST_RESPONSE, RESOLUTION));
    private static final Set<String> STAT_FIELDS = new TreeSet<>(List.of("medianMinutes", "met", "missed"));

    private static GoalStat stat(Long median, long met, long missed) {
        return new GoalStat(median, met, missed);
    }

    /** Staff takes an issue that is {@code RECEIVED} (or reopened) to {@code RESOLVED}. */
    private void resolve(Desk d, Issue issue) {
        flow.moveTo(d.agent(), issue.key(), "RESOLVED");
    }

    private void answer(Desk d, Issue issue) {
        fixtures.comment(d.agent(), issue.key(), "An answer");
    }

    /** Moves the clock to {@code minutes} and {@code seconds} after {@code from}. */
    private void at(Instant from, int minutes, int seconds) {
        sla.advanceTo(from.plus(Duration.ofMinutes(minutes).plusSeconds(seconds)));
    }

    // S8B-AC-21

    @Test
    @DisplayName("S8B-AC-21 days 7, 30, 90, and none (30) give the same five counts of an issue closed 10 days ago; "
            + "its goals count with 30 and 90 and with none, not with 7")
    void daysNarrowsTheSlaNotTheCounts() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), GOALS);
        Issue issue = sla.file(d, "NORMAL");
        at(SlaSteps.createdAt(issue), 10, 0);
        // Closed, not resolved: the automatic closing would change the counts between two reads.
        flow.close(d.customer(), issue.key());
        sla.advanceTo(sla.now().toInstant().plus(Duration.ofDays(10)));
        Session admin = sla.admin();

        JsonNode none = itemOf(admin, null, d.code());
        for (Integer days : new Integer[] {7, 30, 90}) {
            assertThat(StatsSteps.counts(itemOf(admin, days, d.code()))).as("counts with days %s", days)
                    .isEqualTo(StatsSteps.counts(none));
        }
        assertThat(StatsSteps.counts(none)).as("total, open, inProgress, resolved, closed")
                .containsExactly(1L, 0L, 0L, 0L, 1L);
        for (Integer days : new Integer[] {null, 30, 90}) {
            JsonNode item = itemOf(admin, days, d.code());
            assertThat(goal(item, FIRST_RESPONSE)).as("first response with days %s", days)
                    .isEqualTo(stat(null, 1, 0));
            assertThat(goal(item, RESOLUTION)).as("resolution with days %s", days).isEqualTo(stat(10L, 1, 0));
        }
        JsonNode week = itemOf(admin, 7, d.code());
        assertThat(goal(week, FIRST_RESPONSE)).as("first response with days 7").isEqualTo(stat(null, 0, 0));
        assertThat(goal(week, RESOLUTION)).as("resolution with days 7").isEqualTo(stat(null, 0, 0));
    }

    @ParameterizedTest(name = "days={0}")
    @ValueSource(strings = {"0", "1", "14", "31", "60", "365", "-7"})
    @DisplayName("S8B-AC-21 a days other than 7, 30, and 90 is 400 INVALID_INPUT with days UNKNOWN_VALUE")
    void otherDaysAreRefused(String days) {
        Session admin = sla.admin();

        ApiResponse response = SlaStatsSteps.request(admin, days).send();

        ManagerSteps.assertError(response, 400, "INVALID_INPUT");
        assertThat(ManagerSteps.fieldErrors(response)).as("fields of %s", response.snippet())
                .containsExactly("days:UNKNOWN_VALUE");
    }

    // S8B-AC-22

    @Test
    @DisplayName("S8B-AC-22 with the policy off every item has sla null; with a goal set every item has both goals, "
            + "each with medianMinutes, met, and missed")
    void slaIsNullOnlyWhileThePolicyIsOff() {
        Desk d = sla.desk();
        sla.file(d, "NORMAL");
        SlaSteps.turnOff(d.admin());

        List<JsonNode> off = SlaStatsSteps.items(d.admin(), null);
        assertThat(StatsSteps.codes(off)).as("companies with the policy off").contains(d.code());
        assertThat(off.stream().filter(item -> !SlaStatsSteps.sla(item).isNull()).toList())
                .as("items whose sla is not null with the policy off").isEmpty();

        SlaSteps.change(d.admin(), GOALS);
        List<JsonNode> on = SlaStatsSteps.items(d.admin(), null);
        assertThat(StatsSteps.codes(on)).as("companies with the policy on").contains(d.code());
        List<String> wrong = new ArrayList<>();
        for (JsonNode item : on) {
            JsonNode value = SlaStatsSteps.sla(item);
            if (!value.isObject() || !new TreeSet<>(value.propertyNames()).equals(GOAL_NAMES)
                    || !new TreeSet<>(value.path(FIRST_RESPONSE).propertyNames()).equals(STAT_FIELDS)
                    || !new TreeSet<>(value.path(RESOLUTION).propertyNames()).equals(STAT_FIELDS)) {
                wrong.add(StatsSteps.code(item) + ": " + value);
            }
        }
        assertThat(wrong).as("items without both goals and their three fields with the policy on").isEmpty();
    }

    // S8B-AC-23

    @Test
    @DisplayName("S8B-AC-23 with days 7 read 7 days and 1 second after the first resolutions, goals settled 1 second "
            + "before the start do not count and those 1 second after do; NONE, the trash, and an issue answered "
            + "inside after it was settled without an answer outside do not count; an issue reopened and resolved "
            + "again counts once")
    void goalsSettledInThePeriodCountOnce() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), GOALS);
        Issue before = sla.file(d, "NORMAL");
        Issue answeredLater = sla.file(d, "NORMAL");
        Issue inside = sla.file(d, "NORMAL");
        Issue trashed = sla.file(d, "NORMAL");
        Issue noGoal = fixtures.fileIssue(d.customer(), NewIssue.in(d.code()).priority("LOW"));
        Issue reopened = sla.file(d, "NORMAL");
        Instant filed = SlaSteps.createdAt(before);

        at(filed, 10, 0);
        Instant start = sla.now().toInstant();
        resolve(d, before);
        resolve(d, answeredLater);
        at(filed, 10, 2);
        resolve(d, inside);
        resolve(d, trashed);
        resolve(d, noGoal);
        answer(d, reopened);
        resolve(d, reopened);
        flow.reopen(d.customer(), reopened.key());
        flow.reopen(d.customer(), answeredLater.key());
        answer(d, answeredLater);
        TrashSteps.delete(d.admin(), trashed.key());
        at(filed, 13, 2);
        resolve(d, reopened);
        sla.advanceTo(start.plus(Duration.ofDays(7)).plusSeconds(1));
        Session admin = sla.admin();

        JsonNode week = itemOf(admin, 7, d.code());
        // Inside: first response of inside and reopened, resolution of inside and reopened.
        assertThat(List.of(goal(week, FIRST_RESPONSE).met(), goal(week, FIRST_RESPONSE).missed()))
                .as("first response met and missed with days 7: %s", week).containsExactly(2L, 0L);
        assertThat(List.of(goal(week, RESOLUTION).met(), goal(week, RESOLUTION).missed()))
                .as("resolution met and missed with days 7: %s", week).containsExactly(2L, 0L);
        JsonNode month = itemOf(admin, 30, d.code());
        // Also before and answeredLater (settled by resolving); answeredLater runs again in its resolution.
        assertThat(List.of(goal(month, FIRST_RESPONSE).met(), goal(month, FIRST_RESPONSE).missed()))
                .as("first response met and missed with days 30: %s", month).containsExactly(4L, 0L);
        assertThat(List.of(goal(month, RESOLUTION).met(), goal(month, RESOLUTION).missed()))
                .as("resolution met and missed with days 30: %s", month).containsExactly(3L, 0L);
    }

    // S8B-AC-24

    @Test
    @DisplayName("S8B-AC-24 the first response median is the lower one of the four answered goals (10:00, 25:40, "
            + "50:00, 70:00 is 25 minutes), without the goal settled by resolving at 30:00 even after the issue is "
            + "reopened and answered, which counts as met; the resolution median of five is 100 minutes for 100:20; "
            + "a company with nothing settled has null and zeros")
    void lowerMedianInWholeMinutes() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), GOALS);
        Issue[] answered = new Issue[4];
        for (int i = 0; i < answered.length; i++) {
            answered[i] = sla.file(d, "NORMAL");
        }
        Issue unanswered = sla.file(d, "NORMAL");
        String empty = fixtures.createCompany(d.admin()).code();
        fixtures.fileIssue(fixtures.customer(d.admin(), empty), NewIssue.in(empty).priority("NORMAL"));
        Instant filed = SlaSteps.createdAt(answered[0]);

        at(filed, 10, 0);
        answer(d, answered[0]);
        at(filed, 25, 40);
        answer(d, answered[1]);
        at(filed, 30, 0);
        resolve(d, unanswered);
        at(filed, 35, 0);
        flow.reopen(d.customer(), unanswered.key());
        at(filed, 40, 0);
        answer(d, unanswered);
        resolve(d, answered[0]);
        at(filed, 50, 0);
        answer(d, answered[2]);
        at(filed, 55, 0);
        resolve(d, unanswered);
        at(filed, 70, 0);
        answer(d, answered[3]);
        at(filed, 100, 20);
        resolve(d, answered[1]);
        at(filed, 130, 0);
        resolve(d, answered[2]);
        at(filed, 150, 0);
        resolve(d, answered[3]);
        Session admin = sla.admin();

        JsonNode item = itemOf(admin, null, d.code());
        assertThat(goal(item, FIRST_RESPONSE)).as("first response of %s", item).isEqualTo(stat(25L, 4, 1));
        // 40:00, 50:00 (30 before the reopening and 20 after), 100:20, 130:00, 150:00.
        assertThat(goal(item, RESOLUTION)).as("resolution of %s", item).isEqualTo(stat(100L, 3, 2));
        JsonNode nothing = itemOf(admin, null, empty);
        assertThat(Arrays.asList(goal(nothing, FIRST_RESPONSE), goal(nothing, RESOLUTION)))
                .as("goals of a company with nothing settled: %s", nothing)
                .containsExactly(stat(null, 0, 0), stat(null, 0, 0));
    }

    // S8B-AC-25

    @Test
    @DisplayName("S8B-AC-25 an agent assigned to company A gets A alone, with the SLA the admin reads for A and none "
            + "of company B's goals")
    void agentGetsTheSlaOfTheAssignedCompanyOnly() {
        Desk a = sla.desk();
        SlaSteps.change(a.admin(), GOALS);
        String b = fixtures.createCompany(a.admin()).code();
        Session customerOfB = fixtures.customer(a.admin(), b);
        Session agentOfB = fixtures.agent(a.admin(), b);
        Issue ofA = sla.file(a, "NORMAL");
        Issue[] ofB = {fixtures.fileIssue(customerOfB, NewIssue.in(b).priority("NORMAL")),
                fixtures.fileIssue(customerOfB, NewIssue.in(b).priority("NORMAL"))};
        Instant filed = SlaSteps.createdAt(ofA);

        at(filed, 10, 0);
        answer(a, ofA);
        at(filed, 20, 0);
        resolve(a, ofA);
        at(filed, 70, 0);
        flow.moveTo(agentOfB, ofB[0].key(), "RESOLVED");
        at(filed, 130, 0);
        flow.moveTo(agentOfB, ofB[1].key(), "RESOLVED");
        Session admin = sla.admin();

        List<JsonNode> ofAgent = SlaStatsSteps.items(a.agent(), 90);
        assertThat(StatsSteps.codes(ofAgent)).as("companies of the agent of A").containsExactly(a.code());
        JsonNode itemOfA = ofAgent.getFirst();
        assertThat(Arrays.asList(goal(itemOfA, FIRST_RESPONSE), goal(itemOfA, RESOLUTION)))
                .as("goals of A for its agent: %s", itemOfA).containsExactly(stat(10L, 1, 0), stat(20L, 1, 0));
        assertThat(SlaStatsSteps.sla(itemOfA)).as("sla of A for the agent and for the admin")
                .isEqualTo(SlaStatsSteps.sla(itemOf(admin, 90, a.code())));
        JsonNode itemOfB = itemOf(admin, 90, b);
        assertThat(Arrays.asList(goal(itemOfB, FIRST_RESPONSE), goal(itemOfB, RESOLUTION)))
                .as("goals of B for the admin: %s", itemOfB).containsExactly(stat(null, 0, 2), stat(70L, 1, 1));
    }
}
