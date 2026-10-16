package io.popdesk.sla;

import static io.popdesk.sla.SlaAlertSteps.RECALCULATED;
import static io.popdesk.sla.SlaAlertSteps.RESOLUTION_BREACHED;
import static io.popdesk.sla.SlaAlertSteps.RESPONSE_BREACHED;
import static io.popdesk.sla.SlaAlertSteps.RESPONSE_WARNING;
import static io.popdesk.sla.SlaAlertSteps.alertKinds;
import static io.popdesk.sla.SlaAlertSteps.alerts;
import static io.popdesk.sla.SlaAlertSteps.assertAlert;
import static io.popdesk.sla.SlaAlertSteps.oneAlert;
import static io.popdesk.sla.SlaAlertSteps.portalItems;
import static io.popdesk.sla.SlaAlertSteps.slaRowsOf;
import static io.popdesk.sla.SlaAlertSteps.untouched;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;
import static io.popdesk.sla.SlaSteps.state;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.sla.SlaAlertSteps.Untouched;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * SLA alerts in the console inbox as time goes by (S8B-AC-01 to S8B-AC-11): who gets them, once per goal and step,
 * what stops them, and what lets them come again. Every day counts 24 hours here. Runs of {@code sla-recalculate}
 * come at chosen times ({@link SlaAlertSteps#runAt}): the job runs once a minute of app time passed since its last
 * run, so a test first waits for a run ({@link SlaSteps#recalculateNow}) and then moves the clock in one step to the
 * time it wants the next run at.
 *
 * <p>Every active admin of the context gets the overdue items, also those of other classes' issues; items are
 * counted by the issue key, and the admins counted are ones the test made.
 */
class SlaAlertTest extends SlaAppTest {

    private SlaAlertSteps alerts;

    @BeforeEach
    void setUpAlerts() {
        alerts = new SlaAlertSteps(fixtures, clock, sla);
    }

    // S8B-AC-01, S8B-AC-06, S8B-AC-07

    @Test
    @DisplayName("S8B-AC-01, S8B-AC-06, S8B-AC-07 a first response goal of 20 minutes past its warning time at 16 "
            + "is alerted within two runs: the assignee gets one SLA_RESPONSE_WARNING item with the issue, the "
            + "company code, and a null actor, and the issue keeps its version, updatedAt, and history")
    void warningIsAlertedWithinTwoRuns() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 20, null));
        Issue issue = sla.file(d, "NORMAL");
        flow.assign(d.admin(), issue.key(), d.agent().accountId());
        sla.recalculateNow(d.admin());
        Untouched before = untouched(flow, d.agent(), issue.key());

        alerts.runAfterFiling(d.admin(), issue, Duration.ofMinutes(16).plusSeconds(1));
        sla.recalculateNow(d.admin());

        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items of the assignee two runs after 16 minutes")
                .containsExactly(RESPONSE_WARNING);
        assertAlert(oneAlert(d.agent(), issue.key(), RESPONSE_WARNING), RESPONSE_WARNING, issue.key(), d.code());
        assertThat(untouched(flow, d.agent(), issue.key())).as("version, updatedAt, and history length after the "
                + "alert").isEqualTo(before);
    }

    // S8B-AC-02

    @Test
    @DisplayName("S8B-AC-02 a run that finds both goals past their due times, the warnings skipped, gives the "
            + "assignee and an admin one SLA_RESPONSE_BREACHED and one SLA_RESOLUTION_BREACHED item each and no "
            + "warning item")
    void breachSkippingTheWarningIsOneItemPerGoal() {
        Desk d = sla.desk();
        Session admin = alerts.newAdmin(d.admin());
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, 20));
        Issue issue = sla.file(d, "NORMAL");
        flow.assign(d.admin(), issue.key(), d.agent().accountId());
        sla.recalculateNow(d.admin());

        alerts.runAfterFiling(d.admin(), issue, 21);

        for (Session person : List.of(d.agent(), admin)) {
            assertThat(alertKinds(person, issue.key())).as("SLA items of %s", person.role())
                    .containsExactlyInAnyOrder(RESPONSE_BREACHED, RESOLUTION_BREACHED);
            assertAlert(oneAlert(person, issue.key(), RESPONSE_BREACHED), RESPONSE_BREACHED, issue.key(), d.code());
            assertAlert(oneAlert(person, issue.key(), RESOLUTION_BREACHED), RESOLUTION_BREACHED, issue.key(),
                    d.code());
        }
    }

    // S8B-AC-03

    /** Who is around the issue when its first response goal passes 80% and then its due time. */
    enum Side {
        ASSIGNEE,
        NO_ASSIGNEE,
        ASSIGNEE_SUSPENDED,
        ASSIGNEE_OFF_THE_COMPANY,
        NOBODY,
        ADMIN_ASSIGNEE
    }

    /** The people of one case: who should get the warning, who should get the overdue item, and who nothing. */
    private record Cast(List<Session> warned, List<Session> breached, List<Session> none, List<Runnable> undo) {}

    @ParameterizedTest(name = "{0}")
    @EnumSource(Side.class)
    @DisplayName("S8B-AC-03 the warning goes to the active assignee who can see the issue, else to the active agents "
            + "assigned to the company, else to every active admin; the overdue item goes to the same people and "
            + "every active admin once; suspended agents, an assignee taken off the company, and the customers get "
            + "nothing")
    void recipientsOfTheAlerts(Side side) {
        Session first = sla.admin();
        String code = fixtures.createCompany(first).code();
        Session reporter = fixtures.customer(first, code);
        Session colleague = fixtures.customer(first, code);
        Session admin = alerts.newAdmin(first);
        SlaSteps.change(first, Policy.off().target("NORMAL", 10, null));
        Issue issue = fixtures.fileIssue(reporter, NewIssue.in(code).priority("NORMAL"));
        String key = issue.key();
        Cast cast = cast(side, first, code, admin, key);
        sla.recalculateNow(first);

        alerts.runAfterFiling(first, issue, Duration.ofMinutes(9));
        alerts.runAfterFiling(first, issue, Duration.ofMinutes(10).plusSeconds(30));
        cast.undo().forEach(Runnable::run);

        List<Session> warned = signedIn(cast.warned());
        List<Session> breached = signedIn(cast.breached());
        for (Session person : breached) {
            List<String> expected = new ArrayList<>(List.of(RESPONSE_BREACHED));
            if (warned.stream().anyMatch(w -> w.accountId() == person.accountId())) {
                expected.add(RESPONSE_WARNING);
            }
            assertThat(alertKinds(person, key)).as("SLA items of %s %s", person.role(), person.email())
                    .containsExactlyElementsOf(expected);
        }
        for (Session person : signedIn(cast.none())) {
            assertThat(alertKinds(person, key)).as("SLA items of %s %s, who gets none", person.role(),
                    person.email()).isEmpty();
        }
        for (Session customer : List.of(reporter, colleague)) {
            assertThat(portalItems(fixtures.signInAgain(customer), key)).as("portal items of a customer").isEmpty();
            // The portal hides kinds not meant for customers, so only the table shows an SLA row given to one.
            assertThat(slaRowsOf(db, customer)).as("SLA rows of the customer %s in the notification table",
                    customer.email()).isZero();
        }
    }

    private Cast cast(Side side, Session first, String code, Session admin, String key) {
        List<Runnable> undo = new ArrayList<>();
        return switch (side) {
            case ASSIGNEE -> {
                Session assignee = fixtures.agent(first, code);
                Session other = fixtures.agent(first, code);
                flow.assign(first, key, assignee.accountId());
                yield new Cast(List.of(assignee), List.of(assignee, admin), List.of(other), undo);
            }
            case NO_ASSIGNEE -> {
                Session one = fixtures.agent(first, code);
                Session two = fixtures.agent(first, code);
                Session suspended = fixtures.agent(first, code);
                flow.suspend(first, suspended.accountId());
                undo.add(() -> flow.reactivate(sla.admin(), suspended.accountId()));
                yield new Cast(List.of(one, two), List.of(one, two, admin), List.of(suspended), undo);
            }
            case ASSIGNEE_SUSPENDED -> {
                Session assignee = fixtures.agent(first, code);
                Session other = fixtures.agent(first, code);
                flow.assign(first, key, assignee.accountId());
                flow.suspend(first, assignee.accountId());
                undo.add(() -> flow.reactivate(sla.admin(), assignee.accountId()));
                yield new Cast(List.of(other), List.of(other, admin), List.of(assignee), undo);
            }
            case ASSIGNEE_OFF_THE_COMPANY -> {
                Session assignee = fixtures.agent(first, code);
                Session other = fixtures.agent(first, code);
                flow.assign(first, key, assignee.accountId());
                flow.unassignAgent(first, code, assignee.accountId());
                // Assigned again before reading, so that items about the issue would show if there were any.
                undo.add(() -> fixtures.assignAgent(sla.admin(), code, assignee.accountId()));
                yield new Cast(List.of(other), List.of(other, admin), List.of(assignee), undo);
            }
            case NOBODY -> new Cast(List.of(admin), List.of(admin), List.of(), undo);
            case ADMIN_ASSIGNEE -> {
                Session assignee = alerts.newAdmin(first);
                Session agent = fixtures.agent(first, code);
                flow.assign(first, key, assignee.accountId());
                yield new Cast(List.of(assignee), List.of(assignee, admin), List.of(agent), undo);
            }
        };
    }

    private List<Session> signedIn(List<Session> people) {
        return people.stream().map(fixtures::signInAgain).toList();
    }

    // S8B-AC-04

    @Test
    @DisplayName("S8B-AC-04 a resolution goal past its due time is alerted once: three more runs, resolving, and "
            + "reopening while it is still overdue give the assignee and an admin no second SLA_RESOLUTION_BREACHED "
            + "item")
    void oneGoalOneStepOnce() {
        Desk d = sla.desk();
        Session admin = alerts.newAdmin(d.admin());
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", null, 10));
        Issue issue = sla.file(d, "NORMAL");
        flow.assign(d.admin(), issue.key(), d.agent().accountId());
        sla.recalculateNow(d.admin());
        alerts.runAfterFiling(d.admin(), issue, 11);
        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items of the assignee after the due time")
                .containsExactly(RESOLUTION_BREACHED);

        alerts.runs(d.admin(), 3);
        flow.moveTo(d.agent(), issue.key(), "RESOLVED");
        sla.recalculateNow(d.admin());
        flow.reopen(d.customer(), issue.key());
        assertThat(state(goal(sla(d.agent(), issue.key()), RESOLUTION))).as("resolution after reopening")
                .isEqualTo("BREACHED");
        alerts.runs(d.admin(), 2);

        for (Session person : List.of(d.agent(), admin)) {
            assertThat(alertKinds(person, issue.key())).as("SLA items of %s after more runs and a reopening",
                    person.role()).containsExactly(RESOLUTION_BREACHED);
        }
    }

    // S8B-AC-05

    @Test
    @DisplayName("S8B-AC-05 an issue in the trash is not alerted while its goal is past 80%; restored, it is alerted "
            + "at the next run, overdue by then; a first response goal answered before and an issue of a priority "
            + "without a goal are never alerted, while an issue filed with them is")
    void trashSettledAndNoGoalAreNotAlerted() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 1, null));
        Instant start = sla.recalculateNow(d.admin());
        Issue trashed = sla.file(d, "NORMAL");
        Issue running = sla.file(d, "NORMAL");
        Issue answered = sla.file(d, "NORMAL");
        Issue noGoal = sla.file(d, "LOW");
        fixtures.comment(d.agent(), answered.key(), "First answer");
        clock.advance(Duration.ofSeconds(59));
        TrashSteps.delete(d.admin(), trashed.key());

        alerts.runAt(d.admin(), start.plusSeconds(60));
        assertThat(alertKinds(d.agent(), running.key())).as("SLA items about the issue that kept running, at 60 "
                + "seconds").containsExactly(RESPONSE_WARNING);
        TrashSteps.restore(d.admin(), trashed.key());
        assertThat(alertKinds(d.agent(), trashed.key())).as("SLA items about the issue that was in the trash, right "
                + "after the restore").isEmpty();

        alerts.runAt(d.admin(), start.plusSeconds(120));

        assertThat(alertKinds(d.agent(), trashed.key())).as("SLA items about the restored issue after the next run")
                .containsExactly(RESPONSE_BREACHED);
        assertThat(alertKinds(d.agent(), running.key())).as("SLA items about the running issue after the next run")
                .containsExactly(RESPONSE_BREACHED, RESPONSE_WARNING);
        assertThat(alertKinds(d.agent(), answered.key())).as("SLA items about the answered issue").isEmpty();
        assertThat(alertKinds(d.agent(), noGoal.key())).as("SLA items about the LOW issue without a goal").isEmpty();
    }

    // S8B-AC-08

    @Test
    @DisplayName("S8B-AC-08 an overdue first response goal that a lower priority takes back to OK is alerted again: "
            + "a warning at 80% of the new goal and a second overdue item past it")
    void aGoalBackToOkIsAlertedAgain() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, null).target("LOW", 60, null));
        Issue issue = sla.file(d, "NORMAL");
        flow.assign(d.admin(), issue.key(), d.agent().accountId());
        sla.recalculateNow(d.admin());
        alerts.runAfterFiling(d.admin(), issue, 11);
        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items past the goal of 10")
                .containsExactly(RESPONSE_BREACHED);

        flow.edit(d.agent(), issue.key(), Map.of("priority", "LOW"));
        sla.recalculateNow(d.admin());
        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items after LOW, 12 minutes of 60 used")
                .containsExactly(RESPONSE_BREACHED);
        alerts.runAfterFiling(d.admin(), issue, 49);
        alerts.runAfterFiling(d.admin(), issue, 61);

        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items past 48 and 60 minutes, newest first")
                .containsExactly(RESPONSE_BREACHED, RESPONSE_WARNING, RESPONSE_BREACHED);
    }

    // S8B-AC-09

    @Test
    @DisplayName("S8B-AC-09 a goal of 10 minutes lengthened to 60 before any run sees the old times is not alerted "
            + "past the old warning and due times, and is alerted past 48 minutes, 80% of the new goal")
    void aLongerGoalIsAlertedByItsNewTimes() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, null));
        Issue issue = sla.file(d, "NORMAL");
        flow.assign(d.admin(), issue.key(), d.agent().accountId());
        sla.recalculateNow(d.admin());

        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, null));
        alerts.runAfterFiling(d.admin(), issue, 11);
        sla.recalculateNow(d.admin());
        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items past the old due time").isEmpty();
        alerts.runAfterFiling(d.admin(), issue, 49);

        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items past 48 minutes")
                .containsExactly(RESPONSE_WARNING);
    }

    // S8B-AC-10

    @Test
    @DisplayName("S8B-AC-10 an agent who raises the priority so that the first response goal is overdue at once "
            + "gets the SLA_RESPONSE_BREACHED item at the next run, as does an admin, and the admin gets no "
            + "SLA_RECALCULATED item for it")
    void aRaisedPriorityIsAlertedOneByOne() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, null).target("HIGH", 5, null));
        Issue issue = sla.file(d, "NORMAL");
        flow.assign(d.admin(), issue.key(), d.agent().accountId());
        sla.recalculateNow(d.admin());
        Session admin = alerts.newAdmin(d.admin());
        alerts.runAfterFiling(d.admin(), issue, 7);

        flow.edit(d.agent(), issue.key(), Map.of("priority", "HIGH"));
        sla.recalculateNow(d.admin());

        for (Session person : List.of(d.agent(), admin)) {
            assertThat(alertKinds(person, issue.key())).as("SLA items of %s after HIGH", person.role())
                    .containsExactly(RESPONSE_BREACHED);
        }
        assertAlert(oneAlert(d.agent(), issue.key(), RESPONSE_BREACHED), RESPONSE_BREACHED, issue.key(), d.code());
        assertThat(TalkFlow.kinds(TalkFlow.inbox(admin))).as("kinds of the new admin's items")
                .doesNotContain(RECALCULATED);
    }

    // S8B-AC-11

    @Test
    @DisplayName("S8B-AC-11 an agent taken off the company no longer has the SLA items in the list or the unread "
            + "count, and read-all marks another agent's SLA items read")
    void slaItemsFollowTheInboxRules() {
        Desk d = sla.desk();
        Session other = fixtures.agent(d.admin(), d.code());
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, null));
        Issue issue = sla.file(d, "NORMAL");
        sla.recalculateNow(d.admin());
        long agentBefore = TalkFlow.unreadCount(d.agent());
        long otherBefore = TalkFlow.unreadCount(other);
        alerts.runAfterFiling(d.admin(), issue, 11);
        assertThat(alertKinds(d.agent(), issue.key())).as("SLA items of the agent").containsExactly(RESPONSE_BREACHED);
        assertThat(TalkFlow.unreadCount(d.agent())).as("unread count of the agent").isEqualTo(agentBefore + 1);
        assertThat(TalkFlow.unreadCount(other)).as("unread count of the other agent").isEqualTo(otherBefore + 1);

        TalkFlow.markAllRead(other);
        flow.unassignAgent(d.admin(), d.code(), d.agent().accountId());

        assertThat(TalkFlow.unreadCount(other)).as("unread count of the other agent after read-all").isZero();
        JsonNode read = oneAlert(other, issue.key(), RESPONSE_BREACHED);
        assertThat(TalkFlow.isNull(read, "readAt")).as("readAt is set in %s", read).isFalse();
        assertThat(alerts(d.agent(), issue.key())).as("SLA items of the agent taken off the company").isEmpty();
        long unreadListed = TalkFlow.inbox(d.agent()).stream().filter(n -> TalkFlow.isNull(n, "readAt")).count();
        assertThat(TalkFlow.unreadCount(d.agent())).as("unread count of the agent taken off the company")
                .isEqualTo(unreadListed).isLessThanOrEqualTo(agentBefore);
    }
}
