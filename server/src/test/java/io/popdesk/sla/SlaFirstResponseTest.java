package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.assertGoal;
import static io.popdesk.sla.SlaSteps.createdAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;

import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The first response goal is settled once (S8A-AC-13, S8A-AC-14): by the first public comment of staff, or by
 * resolving or closing an issue nobody answered. Every day counts 24 hours here, so a goal runs out its minutes after
 * filing.
 */
class SlaFirstResponseTest extends SlaAppTest {

    /** NORMAL 60 minutes to the first response and 600 to resolution; HIGH no first response goal. */
    private static final Policy GOALS = Policy.off().target("NORMAL", 60, 600).target("HIGH", null, 300);

    private static Instant plus(Issue issue, long minutes) {
        return createdAt(issue).plus(Duration.ofMinutes(minutes));
    }

    // S8A-AC-13

    @Test
    @DisplayName("S8A-AC-13 the first public comment of staff stops the first response goal as MET within the goal, "
            + "MISSED after it, and with state null without a goal; resolving, reopening, another priority, and a "
            + "new policy version afterwards change none of them")
    void firstAnswerSettlesTheGoal() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), GOALS);
        Issue met = sla.file(d, "NORMAL");
        Issue missed = sla.file(d, "NORMAL");
        Issue none = sla.file(d, "HIGH");

        sla.advanceMinutes(40);
        fixtures.comment(d.agent(), met.key(), "Answered in time");
        fixtures.comment(d.agent(), none.key(), "Answered without a goal");
        sla.advanceMinutes(40);
        fixtures.comment(d.agent(), missed.key(), "Answered late");

        assertGoal(goal(sla(d.agent(), met.key()), FIRST_RESPONSE), "MET", plus(met, 60), null, "answered in 40");
        assertGoal(goal(sla(d.agent(), missed.key()), FIRST_RESPONSE), "MISSED", plus(missed, 60), null,
                "answered in 80");
        assertGoal(goal(sla(d.agent(), none.key()), FIRST_RESPONSE), null, null, null, "answered without a goal");

        for (Issue issue : new Issue[] {met, missed, none}) {
            flow.moveTo(d.agent(), issue.key(), "RESOLVED");
            flow.reopen(d.customer(), issue.key());
            flow.edit(d.agent(), issue.key(), Map.of("priority", issue == none ? "NORMAL" : "HIGH"));
        }
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 5, 600).target("HIGH", 5, 300));
        sla.recalculateNow(d.admin());

        assertGoal(goal(sla(d.agent(), met.key()), FIRST_RESPONSE), "MET", plus(met, 60), null,
                "MET after resolving, reopening, HIGH, and a new version");
        assertGoal(goal(sla(d.agent(), missed.key()), FIRST_RESPONSE), "MISSED", plus(missed, 60), null,
                "MISSED after resolving, reopening, HIGH, and a new version");
        assertGoal(goal(sla(d.agent(), none.key()), FIRST_RESPONSE), null, null, null,
                "without a goal after resolving, reopening, NORMAL, and a new version");
    }

    // S8A-AC-14

    /** How an issue nobody answered stops being open. */
    enum Ending {
        STAFF_RESOLVES,
        CUSTOMER_CLOSES
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Ending.class)
    @DisplayName("S8A-AC-14 an issue resolved or closed without a first response settles the first response goal "
            + "then: MET within the goal, MISSED after it, with the due time kept and no minutes left")
    void endingWithoutAnAnswerSettlesTheFirstResponse(Ending ending) {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), GOALS);
        Issue early = sla.file(d, "NORMAL");
        Issue late = sla.file(d, "NORMAL");

        sla.advanceMinutes(30);
        end(d, early, ending);
        sla.advanceMinutes(60);
        end(d, late, ending);
        sla.advanceMinutes(30);

        JsonNode earlySla = sla(d.agent(), early.key());
        assertGoal(goal(earlySla, FIRST_RESPONSE), "MET", plus(early, 60), null, "first response, ended at 30");
        assertGoal(goal(earlySla, RESOLUTION), "MET", plus(early, 600), null, "resolution, ended at 30");
        JsonNode lateSla = sla(d.agent(), late.key());
        assertGoal(goal(lateSla, FIRST_RESPONSE), "MISSED", plus(late, 60), null, "first response, ended at 90");
        assertGoal(goal(lateSla, RESOLUTION), "MET", plus(late, 600), null, "resolution, ended at 90");
    }

    private void end(Desk d, Issue issue, Ending ending) {
        switch (ending) {
            case STAFF_RESOLVES -> flow.moveTo(d.agent(), issue.key(), "RESOLVED");
            case CUSTOMER_CLOSES -> flow.close(d.customer(), issue.key());
        }
    }
}
