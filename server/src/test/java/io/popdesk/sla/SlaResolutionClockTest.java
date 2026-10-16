package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.assertGoal;
import static io.popdesk.sla.SlaSteps.createdAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.trash.TrashJobs;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The resolution goal stops when an issue leaves the open statuses and counts on from what it used when it comes
 * back (S8A-AC-15). Every day counts 24 hours here, so a goal runs out its minutes after filing.
 */
class SlaResolutionClockTest extends SlaAppTest {

    private static Instant plus(Issue issue, long minutes) {
        return createdAt(issue).plus(Duration.ofMinutes(minutes));
    }

    @Test
    @DisplayName("S8A-AC-15 a resolution goal stopped as MET at 100 of 120 minutes stays MET with the same due "
            + "time after the first public comment on the resolved issue, after the customer closes it at 150, and "
            + "after the automatic closing 7 days later; the first response goal settled by resolving stays MISSED")
    void aStoppedGoalStaysStopped() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, 120));
        Issue closed = sla.file(d, "NORMAL");
        Issue autoClosed = sla.file(d, "NORMAL");

        sla.advanceMinutes(100);
        flow.moveTo(d.agent(), closed.key(), "RESOLVED");
        flow.moveTo(d.agent(), autoClosed.key(), "RESOLVED");
        fixtures.comment(d.agent(), closed.key(), "First answer, after resolving");
        sla.advanceMinutes(50);
        flow.close(d.customer(), closed.key());
        Instant resolvedAt = IssueFlow.time(flow.detail(d.agent(), autoClosed.key()), "resolvedAt");
        Instant due = sla.advanceTo(resolvedAt.plus(Duration.ofDays(7).plusMinutes(11)));
        Desk again = sla.signInAgain(d);
        TrashJobs.awaitRun(again.admin(), TrashJobs.AUTO_CLOSE, due);

        assertThat(flow.detail(again.agent(), closed.key()).path("status").asString(null)).as("status of %s",
                closed.key()).isEqualTo("CLOSED");
        assertThat(flow.detail(again.agent(), autoClosed.key()).path("status").asString(null))
                .as("status of %s after the automatic closing", autoClosed.key()).isEqualTo("CLOSED");
        for (Issue issue : new Issue[] {closed, autoClosed}) {
            JsonNode settled = sla(again.agent(), issue.key());
            assertGoal(goal(settled, FIRST_RESPONSE), "MISSED", plus(issue, 60), null,
                    "first response of " + issue.key());
            assertGoal(goal(settled, RESOLUTION), "MET", plus(issue, 120), null, "resolution of " + issue.key());
        }
    }

    /** How a resolved issue becomes open again. */
    enum Opening {
        CUSTOMER_REOPENS,
        STAFF_REOPENS,
        STAFF_SETS_IN_PROGRESS
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Opening.class)
    @DisplayName("S8A-AC-15 a resolution goal of 600 minutes stopped as MET at 100 counts on from 100 when the issue "
            + "is reopened or set IN_PROGRESS 1000 minutes later: OK, 500 minutes left, due 500 minutes after, and "
            + "400 left 100 minutes on; the first response goal settled before stays MET")
    void openingAgainCountsOn(Opening opening) {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 30, 600));
        Issue issue = sla.file(d, "NORMAL");
        sla.advanceMinutes(20);
        fixtures.comment(d.agent(), issue.key(), "First answer");
        sla.advanceMinutes(80);
        flow.moveTo(d.agent(), issue.key(), "RESOLVED");
        assertGoal(goal(sla(d.agent(), issue.key()), RESOLUTION), "MET", plus(issue, 600), null,
                "resolution when resolved");

        Instant openedAt = sla.advanceMinutes(1000);
        Desk again = sla.signInAgain(d);
        switch (opening) {
            case CUSTOMER_REOPENS -> flow.reopen(again.customer(), issue.key());
            case STAFF_REOPENS -> flow.reopen(again.agent(), issue.key());
            case STAFF_SETS_IN_PROGRESS -> flow.changeStatus(again.agent(), issue.key(), "IN_PROGRESS");
        }

        JsonNode opened = sla(again.agent(), issue.key());
        assertGoal(goal(opened, RESOLUTION), "OK", openedAt.plus(Duration.ofMinutes(500)), 500L,
                "resolution when open again");
        assertGoal(goal(opened, FIRST_RESPONSE), "MET", plus(issue, 30), null, "first response when open again");
        sla.advanceMinutes(100);
        assertGoal(goal(sla(again.agent(), issue.key()), RESOLUTION), "OK", openedAt.plus(Duration.ofMinutes(500)),
                400L, "resolution 100 minutes after");
    }
}
