package io.popdesk.sla;

import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.RESOLUTION;
import static io.popdesk.sla.SlaSteps.assertGoal;
import static io.popdesk.sla.SlaSteps.createdAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The state of a running goal and its minutes left, a few points apart (S8A-AC-18, S8A-AC-19). Every day counts 24
 * hours here; NORMAL has 50 minutes to the first response (warning from 40) and 100 to resolution (warning from 80).
 * The issue is filed on a half minute, so due times keep their seconds and minutes left are rounded. The exact edges
 * (80%, the due time itself) are the unit tests of design.md 7절.
 */
class SlaStateTest extends SlaAppTest {

    @Test
    @DisplayName("S8A-AC-18, S8A-AC-19 due times to the second; at 20.5 minutes both OK with 29 and 79 minutes left "
            + "(rounded down); at 42 the first response WARNING; at 85 the first response BREACHED by 35 and "
            + "resolution WARNING with 15 left; at 101.5 both BREACHED by 52 and 2 minutes (rounded up)")
    void stateAndMinutesLeft() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 50, 100));
        int second = sla.now().getSecond();
        clock.advance(Duration.ofSeconds(second < 30 ? 30 - second : 90 - second));
        Issue issue = sla.file(d, "NORMAL");
        Instant filed = createdAt(issue);
        assertThat(sla.local(filed).getSecond()).as("second of createdAt %s", filed).isEqualTo(30);
        Instant firstDue = filed.plus(Duration.ofMinutes(50));
        Instant resolutionDue = filed.plus(Duration.ofMinutes(100));

        clock.advance(Duration.ofSeconds(20 * 60 + 30));
        JsonNode early = sla(d.agent(), issue.key());
        assertGoal(goal(early, FIRST_RESPONSE), "OK", firstDue, 29L, "first response at 20.5 minutes");
        assertGoal(goal(early, RESOLUTION), "OK", resolutionDue, 79L, "resolution at 20.5 minutes");

        clock.advance(Duration.ofSeconds(21 * 60 + 30));
        JsonNode warning = sla(d.agent(), issue.key());
        assertGoal(goal(warning, FIRST_RESPONSE), "WARNING", firstDue, 8L, "first response at 42 minutes");
        assertGoal(goal(warning, RESOLUTION), "OK", resolutionDue, 58L, "resolution at 42 minutes");

        sla.advanceMinutes(43);
        JsonNode mixed = sla(d.agent(), issue.key());
        assertGoal(goal(mixed, FIRST_RESPONSE), "BREACHED", firstDue, -35L, "first response at 85 minutes");
        assertGoal(goal(mixed, RESOLUTION), "WARNING", resolutionDue, 15L, "resolution at 85 minutes");

        clock.advance(Duration.ofSeconds(16 * 60 + 30));
        JsonNode breached = sla(d.agent(), issue.key());
        assertGoal(goal(breached, FIRST_RESPONSE), "BREACHED", firstDue, -52L, "first response at 101.5 minutes");
        assertGoal(goal(breached, RESOLUTION), "BREACHED", resolutionDue, -2L, "resolution at 101.5 minutes");
    }
}
