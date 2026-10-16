package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.assertGoal;
import static io.popdesk.sla.SlaSteps.createdAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.remainingMinutes;
import static io.popdesk.sla.SlaSteps.sla;
import static io.popdesk.sla.SlaSteps.state;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Another priority works out the running goals again at once, from the minutes used and the new goals (S8A-AC-17).
 * Every day counts 24 hours here. The issues are read right after the change, before the clock moves, so no run of
 * {@code sla-recalculate} comes between.
 */
class SlaPriorityTest extends SlaAppTest {

    /** Who changes the priority: an agent on the console, or the customer on the portal. */
    enum Changer {
        STAFF,
        CUSTOMER
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Changer.class)
    @DisplayName("S8A-AC-17 NORMAL changed to HIGH after 100 minutes: the running resolution goal is due 140 minutes "
            + "later (240 - 100) with 140 left, an unanswered first response goal of 30 is breached by 70, and a "
            + "first response goal settled before stays MET with its due time")
    void anotherPriorityChangesTheRunningGoals(Changer changer) {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, 600).target("HIGH", 30, 240));
        Issue answered = sla.file(d, "NORMAL");
        Issue unanswered = sla.file(d, "NORMAL");
        sla.advanceMinutes(20);
        fixtures.comment(d.agent(), answered.key(), "First answer");
        Instant changedAt = sla.advanceMinutes(80);

        Session who = changer == Changer.STAFF ? d.agent() : d.customer();
        flow.edit(who, answered.key(), Map.of("priority", "HIGH"));
        flow.edit(who, unanswered.key(), Map.of("priority", "HIGH"));

        JsonNode settled = sla(d.agent(), answered.key());
        assertGoal(goal(settled, FIRST_RESPONSE), "MET", createdAt(answered).plus(Duration.ofMinutes(60)), null,
                "first response answered at 20");
        assertGoal(goal(settled, RESOLUTION), "OK", changedAt.plus(Duration.ofMinutes(140)), 140L,
                "resolution after HIGH");
        JsonNode running = sla(d.agent(), unanswered.key());
        assertThat(state(goal(running, FIRST_RESPONSE))).as("first response of the unanswered issue after HIGH")
                .isEqualTo("BREACHED");
        assertThat(remainingMinutes(goal(running, FIRST_RESPONSE)))
                .as("first response minutes left of the unanswered issue after HIGH").isEqualTo(-70L);
        assertGoal(goal(running, RESOLUTION), "OK", changedAt.plus(Duration.ofMinutes(140)), 140L,
                "resolution of the unanswered issue after HIGH");
    }
}
