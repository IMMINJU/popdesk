package io.popdesk.sla;

import static io.popdesk.sla.SlaAlertSteps.RESPONSE_BREACHED;
import static io.popdesk.sla.SlaAlertSteps.alertKinds;
import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.createdAt;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.sla;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.CleanupStall;
import io.popdesk.ops.CleanupStall.Job;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.trash.TrashJobs;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The two steps of a run of {@code sla-recalculate} (S8B-AC-28): the alerts and the recalculation do not stop each
 * other. One step is made to fail as {@link CleanupStall} does, with a connection of the test locking a table only
 * that step writes: {@code sla_recalc_report} for the recalculation (an issue newly breached adds to the open summary
 * in the same transaction as its clocks), {@code notification} for the alerts. The run fails and is not recorded;
 * the other step did its work in that run; the next run does the rest. Every day counts 24 hours here.
 *
 * <p>Two issues: one caught up with the new policy by a status change of the agent (no step changes, so nothing is
 * added to a summary), which the alert step finds overdue 12 minutes after filing, and one left at the old version,
 * which the recalculation finds newly breached then.
 */
class SlaRunStepsTest extends SlaAppTest {

    private static final Policy BEFORE = Policy.off().target("NORMAL", 60, null);
    private static final Policy AFTER = Policy.off().target("NORMAL", 10, null);

    @Autowired
    PostgreSQLContainer postgres;

    /** The step made to fail, by the table locked. */
    enum Failing {
        RECALCULATION("sla_recalc_report"),
        ALERTS("notification");

        final String table;

        Failing(String table) {
            this.table = table;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failing.class)
    @DisplayName("S8B-AC-28 when the recalculation of a run fails (sla_recalc_report locked) the alert step of that "
            + "run still alerts the overdue issue, and when the alert step fails (notification locked) the "
            + "recalculation still works out the other issue; the run is not recorded, and the next run of "
            + "sla-recalculate does the rest and counts the issues it worked out")
    void oneFailingStepLeavesTheOther(Failing failing) {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), BEFORE);
        Issue overdue = sla.file(d, "NORMAL");
        Issue recalculated = sla.file(d, "NORMAL");
        Instant lastRun = sla.recalculateNow(d.admin());
        SlaSteps.change(d.admin(), AFTER);
        flow.changeStatus(d.agent(), overdue.key(), "CONFIRMED");
        Instant failedAt = createdAt(overdue).plus(Duration.ofMinutes(12));

        new CleanupStall(fixtures, db, postgres, clock, log).failAt(failedAt,
                new Job(SlaSteps.JOB, failing.table, Duration.ofMinutes(1)));

        JsonNode line = TrashJobs.run(d.admin(), SlaSteps.JOB, lastRun);
        assertThat(line).as("sla-recalculate in the system status").isNotNull();
        assertThat(TrashJobs.lastRunAt(line)).as("last run of sla-recalculate after the failed run at %s", failedAt)
                .isBefore(failedAt);
        Instant dueWhenFailed = SlaSteps.dueAt(goal(sla(d.agent(), recalculated.key()), FIRST_RESPONSE));
        if (failing == Failing.RECALCULATION) {
            assertThat(alertKinds(d.agent(), overdue.key())).as("SLA items of the overdue issue after the failed run")
                    .containsExactly(RESPONSE_BREACHED);
            assertThat(dueWhenFailed).as("first response due time of the issue the failed recalculation left")
                    .isEqualTo(createdAt(recalculated).plus(Duration.ofMinutes(60)));
        } else {
            assertThat(alertKinds(d.agent(), overdue.key())).as("SLA items of the overdue issue after the failed run")
                    .isEmpty();
            assertThat(dueWhenFailed).as("first response due time of the issue worked out in the failed run")
                    .isEqualTo(createdAt(recalculated).plus(Duration.ofMinutes(10)));
        }

        Instant next = sla.recalculateNow(d.admin());
        JsonNode nextLine = SlaSteps.awaitRecalculation(d.admin(), next);

        assertThat(nextLine.path("name").asString(null)).as("name in %s", nextLine).isEqualTo(SlaSteps.JOB);
        if (failing == Failing.RECALCULATION) {
            assertThat(TrashJobs.count(nextLine)).as("issues worked out again by the next run: %s", nextLine)
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(alertKinds(d.agent(), overdue.key())).as("SLA items of the overdue issue after the next run")
                .containsExactly(RESPONSE_BREACHED);
        assertThat(alertKinds(d.agent(), recalculated.key()))
                .as("SLA items of the issue the recalculation counted as newly breached").isEmpty();
        assertThat(SlaSteps.dueAt(goal(sla(d.agent(), recalculated.key()), FIRST_RESPONSE)))
                .as("first response due time after the next run")
                .isEqualTo(createdAt(recalculated).plus(Duration.ofMinutes(10)));
    }
}
