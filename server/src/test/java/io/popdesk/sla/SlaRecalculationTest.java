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

import io.popdesk.issue.IssueFlow;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.trash.TrashJobs;
import io.popdesk.trash.TrashRows;
import io.popdesk.trash.TrashSteps;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code sla-recalculate} after a new policy version (S8A-AC-20, S8A-AC-22 to S8A-AC-25, and the part of S8A-AC-21
 * that the job leaves {@code version} and {@code updatedAt} alone). Times are wall-clock times in {@code Asia/Seoul}.
 *
 * <p>Before a change each test waits for a run of the job at the clock's time ({@link SlaSteps#recalculateNow}): the
 * job runs when a minute of app time passed since its last run, so it does not run again until the clock moves a
 * minute, and a later run is the first one that sees the change.
 */
class SlaRecalculationTest extends SlaAppTest {

    private static final Policy ROUND_THE_CLOCK = Policy.off().target("NORMAL", null, 600);
    private static final Policy OFFICE = Policy.off().weekdays("09:00", "18:00").target("NORMAL", null, 600);

    private static Object slaVersion(TrashRows rows, Issue issue) {
        return rows.issue(issue.key()).get("sla_version");
    }

    // S8A-AC-20, S8A-AC-21

    @Test
    @DisplayName("S8A-AC-20, S8A-AC-21 a run 19 hours after the change to business hours counts the 100 minutes "
            + "used before the change with the old version and the 500 left from the change: due Monday 16:20 with "
            + "440 left on Saturday, and the issue keeps its version and updatedAt, so an edit with the version read "
            + "before is 200")
    void aLateRunCountsFromTheChange() {
        Instant filedAt = sla.moveToNext(DayOfWeek.FRIDAY, LocalTime.of(15, 20));
        LocalDate friday = sla.local(filedAt).toLocalDate();
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), ROUND_THE_CLOCK);
        Issue issue = sla.file(d, "NORMAL");
        sla.advanceTo(sla.at(friday, LocalTime.of(16, 59)));
        Instant changedAt = sla.recalculateNow(d.admin());
        assertThat(sla.local(changedAt).toLocalTime()).as("time of the change").isEqualTo(LocalTime.of(17, 0));
        JsonNode before = flow.detail(d.agent(), issue.key());

        SlaSteps.change(d.admin(), OFFICE);
        sla.advanceTo(sla.at(friday.plusDays(1), LocalTime.of(12, 0)));
        Desk again = sla.signInAgain(d);
        sla.recalculateNow(again.admin());

        assertGoal(goal(sla(again.agent(), issue.key()), RESOLUTION), "OK",
                sla.at(friday.plusDays(3), LocalTime.of(16, 20)), 440L, "resolution after the run");
        JsonNode after = flow.detail(again.agent(), issue.key());
        assertThat(after.path("version").asLong(-1)).as("version after the run")
                .isEqualTo(before.path("version").asLong(-2));
        assertThat(IssueFlow.time(after, "updatedAt")).as("updatedAt after the run")
                .isEqualTo(IssueFlow.time(before, "updatedAt"));
        IssueFlow.editRequest(again.agent(), issue.key(), Map.of("title", "Edited after the run",
                "version", before.path("version").asLong())).expect(200);
    }

    // S8A-AC-22

    /** One change after the breach: the goal of NORMAL, and what the issue shows after the next run. */
    private record Shrink(int resolution, String state, long left) {}

    @Test
    @DisplayName("S8A-AC-22 an issue breached by 30 minutes stays BREACHED by 30 when the past part of today becomes "
            + "a holiday, is BREACHED by 90 when the goal shrinks to 60, and comes back as WARNING with 30 left at a "
            + "goal of 180 and OK with 450 left at 600: the minutes used never shrink")
    void usedMinutesNeverShrink() {
        Instant filedAt = sla.moveToNext(DayOfWeek.TUESDAY, LocalTime.of(9, 0));
        LocalDate today = sla.local(filedAt).toLocalDate();
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", null, 120));
        Issue issue = sla.file(d, "NORMAL");
        sla.advanceMinutes(149);
        sla.recalculateNow(d.admin());
        JsonNode breached = goal(sla(d.agent(), issue.key()), RESOLUTION);
        assertThat(state(breached)).as("state 150 minutes after filing").isEqualTo("BREACHED");
        assertThat(remainingMinutes(breached)).as("minutes left 150 minutes after filing").isEqualTo(-30L);

        for (Shrink step : new Shrink[] {new Shrink(120, "BREACHED", -30), new Shrink(60, "BREACHED", -90),
                new Shrink(180, "WARNING", 30), new Shrink(600, "OK", 450)}) {
            // Today stays a holiday, so no minute counts after the first change.
            SlaSteps.change(d.admin(), Policy.off().holiday(today).target("NORMAL", null, step.resolution()));
            sla.recalculateNow(d.admin());
            JsonNode after = goal(sla(d.agent(), issue.key()), RESOLUTION);
            assertThat(state(after)).as("state at a goal of %s with today a holiday", step.resolution())
                    .isEqualTo(step.state());
            assertThat(remainingMinutes(after)).as("minutes left at a goal of %s with today a holiday",
                    step.resolution()).isEqualTo(step.left());
        }
    }

    // S8A-AC-23

    @Test
    @DisplayName("S8A-AC-23 a run after two changes counts each stretch with its own version: 120 minutes of 24 hours "
            + "from Friday 15:00, 60 business minutes from 17:00 to 23:00, and the 420 left from 23:00 again 24 "
            + "hours a day, due Saturday 06:00")
    void skippedVersionsCountTheirOwnStretch() {
        Instant filedAt = sla.moveToNext(DayOfWeek.FRIDAY, LocalTime.of(15, 0));
        LocalDate friday = sla.local(filedAt).toLocalDate();
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), ROUND_THE_CLOCK);
        Issue issue = sla.file(d, "NORMAL");
        sla.advanceTo(sla.at(friday, LocalTime.of(16, 59)));
        sla.recalculateNow(d.admin());

        SlaSteps.change(d.admin(), OFFICE);
        sla.advanceTo(sla.at(friday, LocalTime.of(23, 0)));
        SlaSteps.change(d.admin(), ROUND_THE_CLOCK);
        sla.recalculateNow(d.admin());

        assertGoal(goal(sla(d.agent(), issue.key()), RESOLUTION), "OK",
                sla.at(friday.plusDays(1), LocalTime.of(6, 0)), 419L, "resolution at 23:01");
    }

    // S8A-AC-24

    @Test
    @DisplayName("S8A-AC-24 a run after a new version leaves the calculation version of a resolved issue and of one "
            + "in the trash, and the due times of the settled goals; restored or reopened, each runs on with the "
            + "version of now: 30 and 570 minutes left counted in the new business hours")
    void stoppedIssuesWaitForTheirRestart() {
        Instant filedAt = sla.moveToNext(DayOfWeek.MONDAY, LocalTime.of(10, 0));
        LocalDate monday = sla.local(filedAt).toLocalDate();
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 60, 600));
        Issue resolved = sla.file(d, "NORMAL");
        Issue trashed = sla.file(d, "NORMAL");
        sla.advanceMinutes(30);
        flow.moveTo(d.agent(), resolved.key(), "RESOLVED");
        TrashSteps.delete(d.admin(), trashed.key());
        sla.recalculateNow(d.admin());
        TrashRows rows = new TrashRows(db);
        Object resolvedVersion = slaVersion(rows, resolved);
        Object trashedVersion = slaVersion(rows, trashed);

        JsonNode office = SlaSteps.change(d.admin(),
                Policy.off().weekdays("09:00", "18:00").target("NORMAL", 60, 600));
        sla.recalculateNow(d.admin());

        assertThat(slaVersion(rows, resolved)).as("sla_version of the resolved issue after the run")
                .isNotNull().isEqualTo(resolvedVersion);
        assertThat(slaVersion(rows, trashed)).as("sla_version of the issue in the trash after the run")
                .isNotNull().isEqualTo(trashedVersion);
        JsonNode settled = sla(d.agent(), resolved.key());
        assertGoal(goal(settled, FIRST_RESPONSE), "MET", createdAt(resolved).plus(Duration.ofMinutes(60)), null,
                "first response of the resolved issue after the run");
        assertGoal(goal(settled, RESOLUTION), "MET", createdAt(resolved).plus(Duration.ofMinutes(600)), null,
                "resolution of the resolved issue after the run");

        Instant restartedAt = sla.now().toInstant();
        TrashSteps.restore(d.admin(), trashed.key());
        flow.reopen(d.customer(), resolved.key());

        // From Monday 10:32: 448 minutes to 18:00, the other 122 from Tuesday 09:00.
        Instant resolutionDue = sla.at(monday.plusDays(1), LocalTime.of(11, 2));
        JsonNode restored = sla(d.agent(), trashed.key());
        assertGoal(goal(restored, FIRST_RESPONSE), "OK", restartedAt.plus(Duration.ofMinutes(30)), 30L,
                "first response after the restore");
        assertGoal(goal(restored, RESOLUTION), "OK", resolutionDue, 570L, "resolution after the restore");
        assertGoal(goal(sla(d.agent(), resolved.key()), RESOLUTION), "OK", resolutionDue, 570L,
                "resolution after reopening");
        for (Issue issue : new Issue[] {resolved, trashed}) {
            assertThat(((Number) slaVersion(rows, issue)).intValue()).as("sla_version of %s running again",
                    issue.key()).isEqualTo(SlaSteps.version(office));
        }
    }

    // S8A-AC-25

    @Test
    @DisplayName("S8A-AC-25 the system status lists sla-recalculate with the time of its last run and the number of "
            + "issues it worked out again, at least the two running ones of this test")
    void theRunShowsInTheSystemStatus() {
        Desk d = sla.desk();
        SlaSteps.change(d.admin(), ROUND_THE_CLOCK);
        Issue first = sla.file(d, "NORMAL");
        Issue second = sla.file(d, "NORMAL");
        sla.recalculateNow(d.admin());

        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", null, 900));
        Instant at = sla.recalculateNow(d.admin());
        JsonNode line = SlaSteps.awaitRecalculation(d.admin(), at, 2);

        assertThat(line.path("name").asString(null)).as("name in %s", line).isEqualTo(SlaSteps.JOB);
        assertThat(TrashJobs.lastRunAt(line)).as("lastRunAt in %s", line).isAfterOrEqualTo(at);
        assertThat(TrashJobs.count(line)).as("issues worked out again in %s", line).isGreaterThanOrEqualTo(2);
        for (Issue issue : new Issue[] {first, second}) {
            assertThat(SlaSteps.dueAt(goal(sla(d.agent(), issue.key()), RESOLUTION)))
                    .as("resolution due time of %s after the run", issue.key())
                    .isEqualTo(createdAt(issue).plus(Duration.ofMinutes(900)));
        }
    }
}
