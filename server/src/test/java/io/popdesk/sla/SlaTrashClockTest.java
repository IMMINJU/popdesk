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
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The trash pauses the goals that run and leaves settled ones (S8A-AC-16). Every day counts 24 hours here. Three
 * issues go to the trash 30 minutes after filing and come back 1000 minutes later: one nobody answered (both goals
 * run), one answered at 20 (the first response settled, resolution runs), and one resolved at 30 (both settled).
 */
class SlaTrashClockTest extends SlaAppTest {

    private static Instant plus(Instant from, long minutes) {
        return from.plus(Duration.ofMinutes(minutes));
    }

    @Test
    @DisplayName("S8A-AC-16 deleting an issue pauses its running goals with the minutes used so far, and restoring it "
            + "1000 minutes later runs only those on from the restore: 30 and 570 minutes left with due times from "
            + "the restore; a goal settled before the delete stays MET with its due time and no minutes left")
    void trashPausesRunningGoals() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, 600));
        Issue unanswered = sla.file(d, "NORMAL");
        Issue answered = sla.file(d, "NORMAL");
        Issue resolved = sla.file(d, "NORMAL");

        sla.advanceMinutes(20);
        fixtures.comment(d.agent(), answered.key(), "First answer");
        sla.advanceMinutes(10);
        flow.moveTo(d.agent(), resolved.key(), "RESOLVED");
        for (Issue issue : new Issue[] {unanswered, answered, resolved}) {
            TrashSteps.delete(d.admin(), issue.key());
        }
        Instant restoredAt = sla.advanceMinutes(1000);
        Desk again = sla.signInAgain(d);
        for (Issue issue : new Issue[] {unanswered, answered, resolved}) {
            TrashSteps.restore(again.admin(), issue.key());
        }

        JsonNode both = sla(again.agent(), unanswered.key());
        assertGoal(goal(both, FIRST_RESPONSE), "OK", plus(restoredAt, 30), 30L, "first response, unanswered");
        assertGoal(goal(both, RESOLUTION), "OK", plus(restoredAt, 570), 570L, "resolution, unanswered");
        JsonNode one = sla(again.agent(), answered.key());
        assertGoal(goal(one, FIRST_RESPONSE), "MET", plus(createdAt(answered), 60), null,
                "first response, answered at 20");
        assertGoal(goal(one, RESOLUTION), "OK", plus(restoredAt, 570), 570L, "resolution, answered at 20");
        JsonNode none = sla(again.agent(), resolved.key());
        assertGoal(goal(none, FIRST_RESPONSE), "MET", plus(createdAt(resolved), 60), null,
                "first response, resolved at 30");
        assertGoal(goal(none, RESOLUTION), "MET", plus(createdAt(resolved), 600), null, "resolution, resolved at 30");
    }
}
