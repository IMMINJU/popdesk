package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.canonical;
import static io.popdesk.sla.SlaSteps.dueAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.listedSla;
import static io.popdesk.sla.SlaSteps.remainingMinutes;
import static io.popdesk.sla.SlaSteps.sla;
import static io.popdesk.sla.SlaSteps.state;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@code sla} field of console issues and list items (S8A-AC-30), and the due times an issue gets when it is
 * filed while SLA is on (S8A-AC-10). The clock calculations themselves are the unit tests of design.md 7절; here one
 * or two cases each.
 */
class SlaFieldsTest extends SlaAppTest {

    /** Every day counts 24 hours: due times are the filing time plus the goal. */
    private static final Policy ROUND_THE_CLOCK = Policy.off()
            .target("NORMAL", 100, 1000)
            .target("HIGH", null, 100);

    private static Instant createdAt(Issue issue) {
        return IssueFlow.time(issue.json(), "createdAt");
    }

    private static void assertGoal(JsonNode goal, String state, Instant dueAt, Long remaining, String what) {
        assertThat(state(goal)).as("state of %s: %s", what, goal).isEqualTo(state);
        assertThat(dueAt(goal)).as("dueAt of %s: %s", what, goal)
                .isEqualTo(dueAt == null ? null : dueAt.truncatedTo(ChronoUnit.SECONDS));
        assertThat(remainingMinutes(goal)).as("remainingMinutes of %s: %s", what, goal).isEqualTo(remaining);
    }

    /** The {@code sla} of the detail, after checking that the list item gives the same. */
    private static JsonNode bothViews(Session staff, String code, Issue issue) {
        JsonNode detail = sla(staff, issue.key());
        assertThat(canonical(listedSla(staff, code, issue.key()))).as("sla of %s in the list", issue.key())
                .isEqualTo(canonical(detail));
        return detail;
    }

    // S8A-AC-30

    @Test
    @DisplayName("S8A-AC-30 the console detail and list item give sla: null for an issue not under SLA; for one "
            + "under SLA firstResponse and resolution with state, dueAt, and remainingMinutes, state, dueAt, and "
            + "remainingMinutes null for a priority without the goal, dueAt kept and remainingMinutes null once "
            + "the goal is MET")
    void slaFields() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        SlaSteps.turnOff(admin);
        Issue outside = fixtures.fileIssue(customer, NewIssue.in(code).priority("NORMAL"));
        SlaSteps.change(admin, ROUND_THE_CLOCK);
        flow.tick();
        Issue normal = fixtures.fileIssue(customer, NewIssue.in(code).priority("NORMAL"));
        Issue high = fixtures.fileIssue(customer, NewIssue.in(code).priority("HIGH"));
        Issue low = fixtures.fileIssue(customer, NewIssue.in(code).priority("LOW"));
        Instant filed = createdAt(normal);
        Instant firstDue = filed.plus(Duration.ofMinutes(100));
        Instant resolutionDue = filed.plus(Duration.ofMinutes(1000));

        assertThat(sla(agent, outside.key()).isNull()).as("sla of the issue filed while SLA was off").isTrue();
        assertThat(listedSla(agent, code, outside.key()).isNull()).as("its sla in the list").isTrue();
        JsonNode atFiling = bothViews(agent, code, normal);
        assertGoal(goal(atFiling, FIRST_RESPONSE), "OK", firstDue, 100L, "first response at filing");
        assertGoal(goal(atFiling, RESOLUTION), "OK", resolutionDue, 1000L, "resolution at filing");
        JsonNode highSla = bothViews(agent, code, high);
        assertGoal(goal(highSla, FIRST_RESPONSE), null, null, null, "first response of HIGH, without the goal");
        assertGoal(goal(highSla, RESOLUTION), "OK", createdAt(high).plus(Duration.ofMinutes(100)), 100L,
                "resolution of HIGH");
        JsonNode lowSla = bothViews(agent, code, low);
        for (String name : List.of(FIRST_RESPONSE, RESOLUTION)) {
            assertGoal(goal(lowSla, name), null, null, null, name + " of LOW, without goals");
        }

        sla.advanceMinutes(30);
        JsonNode running = bothViews(agent, code, normal);
        assertGoal(goal(running, FIRST_RESPONSE), "OK", firstDue, 70L, "first response after 30 minutes");
        fixtures.comment(agent, normal.key(), "First answer");
        JsonNode answered = bothViews(agent, code, normal);
        assertGoal(goal(answered, FIRST_RESPONSE), "MET", firstDue, null, "first response after the answer");
        assertGoal(goal(answered, RESOLUTION), "OK", resolutionDue, 970L, "resolution after the answer");

        sla.advanceMinutes(10);
        flow.moveTo(agent, normal.key(), "RESOLVED");
        JsonNode resolved = bothViews(agent, code, normal);
        assertGoal(goal(resolved, FIRST_RESPONSE), "MET", firstDue, null, "first response after resolving");
        assertGoal(goal(resolved, RESOLUTION), "MET", resolutionDue, null, "resolution after resolving");
    }

    // S8A-AC-10

    @Test
    @DisplayName("S8A-AC-10 with business hours Monday to Friday 09:00-18:00 in Asia/Seoul, an issue a customer "
            + "files and one an agent files on behalf on Tuesday 20:00 are both under SLA: first response due "
            + "Wednesday 10:00 (60 minutes) and resolution Thursday 10:00 (600 minutes), counted from the next "
            + "opening")
    void filedWhileOnGetsDueTimes() {
        Instant tuesdayEvening = sla.moveToNext(DayOfWeek.TUESDAY, LocalTime.of(20, 0));
        Session admin = sla.admin();
        String code = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        SlaSteps.change(admin, Policy.off().target("NORMAL", 60, 600).weekdays("09:00", "18:00"));

        Issue filed = fixtures.fileIssue(customer, NewIssue.in(code).priority("NORMAL"));
        Issue onBehalf = flow.fileOnBehalf(agent, OnBehalf.of(code, customer.accountId()).priority("NORMAL"));

        LocalDate wednesday = sla.local(tuesdayEvening).toLocalDate().plusDays(1);
        Instant firstDue = sla.at(wednesday, LocalTime.of(10, 0));
        Instant resolutionDue = sla.at(wednesday.plusDays(1), LocalTime.of(10, 0));
        for (Issue issue : List.of(filed, onBehalf)) {
            assertThat(createdAt(issue)).as("createdAt of %s", issue.key()).isEqualTo(tuesdayEvening);
            JsonNode due = bothViews(agent, code, issue);
            assertGoal(goal(due, FIRST_RESPONSE), "OK", firstDue, 60L, "first response of " + issue.key());
            assertGoal(goal(due, RESOLUTION), "OK", resolutionDue, 600L, "resolution of " + issue.key());
        }
    }
}
