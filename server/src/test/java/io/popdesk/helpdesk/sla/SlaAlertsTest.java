package io.popdesk.helpdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.sla.SlaAlerts.Alert;
import io.popdesk.helpdesk.sla.SlaAlerts.Change;
import io.popdesk.helpdesk.sla.SlaAlerts.Mark;
import io.popdesk.helpdesk.sla.SlaAlerts.Recalculated;
import io.popdesk.helpdesk.sla.SlaAlerts.Stage;
import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import io.popdesk.helpdesk.sla.SlaClocks.Outcome;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the SLA alerts decide for one issue (S8B-AC-01, 02, 04, 08, 12, 13, 23; slice 8b design 3절 and 9절): the
 * step of a goal, what the alert step tells, how far a write lowers a mark, what a recalculation counts and marks,
 * and when the first response was settled. Written from the Javadoc of {@link SlaAlerts} alone; the expected values
 * are worked out by hand. Clocks are built directly, with times in seconds from T0.
 */
class SlaAlertsTest {

    private static final Instant T0 = Instant.parse("2026-10-12T00:00:00Z");

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    /** Goal 60 minutes running from T0: warning at 2880 s, due at 3600 s. */
    private static final Clock RUNNING = new Clock(0, T0, null, at(2880), at(3600));

    private static final Instant WARN = at(2880);
    private static final Instant DUE = at(3600);

    private static final Clock NO_GOAL = new Clock(600, T0, null, null, null);
    private static final Clock PAUSED = new Clock(600, null, null, null, null);
    private static final Clock MET = new Clock(900, null, Outcome.MET, WARN, DUE);
    private static final Clock MISSED = new Clock(9000, null, Outcome.MISSED, WARN, DUE);
    private static final Clock NONE = new Clock(900, null, Outcome.NONE, null, null);

    /** The time the recalculation tests judge at, and running clocks whose step at it is known. */
    private static final Instant NOW = at(5000);

    private static final Clock OK_NOW = new Clock(0, T0, null, at(8000), at(10_000));
    private static final Clock WARNING_NOW = new Clock(0, T0, null, at(4000), at(10_000));
    private static final Clock BREACHED_NOW = new Clock(0, T0, null, at(2880), at(3600));
    private static final Clock NO_GOAL_NOW = new Clock(0, T0, null, null, null);

    private static List<Mark> marks() {
        return Arrays.asList(null, Mark.WARNING, Mark.BREACHED);
    }

    // ---- stage ----

    @Test
    @DisplayName("design 3절 a running goal is OK before the warning, WARNING through the due time, BREACHED after")
    void stageEdges() {
        assertThat(SlaAlerts.stage(RUNNING, T0)).isEqualTo(Stage.OK);
        assertThat(SlaAlerts.stage(RUNNING, WARN.minusNanos(1))).isEqualTo(Stage.OK);
        assertThat(SlaAlerts.stage(RUNNING, WARN)).isEqualTo(Stage.WARNING);
        assertThat(SlaAlerts.stage(RUNNING, DUE.minusSeconds(1))).isEqualTo(Stage.WARNING);
        assertThat(SlaAlerts.stage(RUNNING, DUE)).isEqualTo(Stage.WARNING);
        assertThat(SlaAlerts.stage(RUNNING, DUE.plusNanos(1))).isEqualTo(Stage.BREACHED);
        assertThat(SlaAlerts.stage(RUNNING, DUE.plusSeconds(1))).isEqualTo(Stage.BREACHED);
        assertThat(SlaAlerts.stage(RUNNING, DUE.plusSeconds(86_400 * 30))).isEqualTo(Stage.BREACHED);
    }

    @Test
    @DisplayName("design 3절 a running clock without a due time is NO_GOAL whatever the time")
    void stageNoGoal() {
        for (Instant now : List.of(T0, WARN, DUE, DUE.plusSeconds(86_400))) {
            assertThat(SlaAlerts.stage(NO_GOAL, now)).as("%s", now).isEqualTo(Stage.NO_GOAL);
        }
    }

    @Test
    @DisplayName("design 3절 a stopped clock, settled (MET, MISSED, NONE) or paused in the trash, is STOPPED")
    void stageStopped() {
        for (Instant now : List.of(T0, WARN, DUE, DUE.plusSeconds(1), DUE.plusSeconds(86_400))) {
            assertThat(SlaAlerts.stage(MET, now)).as("met %s", now).isEqualTo(Stage.STOPPED);
            assertThat(SlaAlerts.stage(MISSED, now)).as("missed %s", now).isEqualTo(Stage.STOPPED);
            assertThat(SlaAlerts.stage(NONE, now)).as("none %s", now).isEqualTo(Stage.STOPPED);
            assertThat(SlaAlerts.stage(PAUSED, now)).as("paused %s", now).isEqualTo(Stage.STOPPED);
        }
    }

    @Test
    @DisplayName("design 9절 a goal used up when it ran on (due time equal to since) is BREACHED from that instant")
    void stageUsedUpWhenRunOn() {
        Clock usedUp = new Clock(3600, T0, null, T0, T0);
        assertThat(SlaAlerts.stage(usedUp, T0)).isEqualTo(Stage.BREACHED);
        assertThat(SlaAlerts.stage(usedUp, T0.plusNanos(1))).isEqualTo(Stage.BREACHED);
        assertThat(SlaAlerts.stage(usedUp, at(86_400))).isEqualTo(Stage.BREACHED);
        Clock over = new Clock(9000, T0, null, T0, T0);
        assertThat(SlaAlerts.stage(over, T0)).isEqualTo(Stage.BREACHED);
        // past 80% only: the warning is at since, the due time later; at since it is a warning, not overdue
        Clock past80 = new Clock(3000, T0, null, T0, at(600));
        assertThat(SlaAlerts.stage(past80, T0)).isEqualTo(Stage.WARNING);
        assertThat(SlaAlerts.stage(past80, at(600))).isEqualTo(Stage.WARNING);
        assertThat(SlaAlerts.stage(past80, at(601))).isEqualTo(Stage.BREACHED);
    }

    // ---- alert ----

    @Test
    @DisplayName("S8B-AC-01 a goal in warning without a mark is told the warning and marked WARNING")
    void s8bAc01_warningWithoutMarkIsTold() {
        assertThat(SlaAlerts.alert(RUNNING, null, WARN)).isEqualTo(new Alert(Mark.WARNING, Mark.WARNING));
        assertThat(SlaAlerts.alert(RUNNING, null, at(3000))).isEqualTo(new Alert(Mark.WARNING, Mark.WARNING));
    }

    @Test
    @DisplayName("S8B-AC-02 an overdue goal not marked BREACHED is told it is overdue, also skipping the warning")
    void s8bAc02_overdueIsToldOnce() {
        // mark null: the warning was never told; one item, BREACHED, not two
        assertThat(SlaAlerts.alert(RUNNING, null, DUE.plusSeconds(1)))
                .isEqualTo(new Alert(Mark.BREACHED, Mark.BREACHED));
        assertThat(SlaAlerts.alert(RUNNING, Mark.WARNING, DUE.plusSeconds(1)))
                .isEqualTo(new Alert(Mark.BREACHED, Mark.BREACHED));
        assertThat(SlaAlerts.alert(RUNNING, Mark.WARNING, DUE.plusNanos(1)))
                .isEqualTo(new Alert(Mark.BREACHED, Mark.BREACHED));
        // used up when it ran on: overdue at since itself
        assertThat(SlaAlerts.alert(new Clock(3600, T0, null, T0, T0), Mark.WARNING, T0))
                .isEqualTo(new Alert(Mark.BREACHED, Mark.BREACHED));
    }

    @Test
    @DisplayName("S8B-AC-04 a step already marked is not told again")
    void s8bAc04_markedStepIsNotToldAgain() {
        assertThat(SlaAlerts.alert(RUNNING, Mark.WARNING, WARN)).isEqualTo(new Alert(null, Mark.WARNING));
        assertThat(SlaAlerts.alert(RUNNING, Mark.WARNING, at(3000))).isEqualTo(new Alert(null, Mark.WARNING));
        assertThat(SlaAlerts.alert(RUNNING, Mark.BREACHED, DUE.plusSeconds(1)))
                .isEqualTo(new Alert(null, Mark.BREACHED));
        assertThat(SlaAlerts.alert(RUNNING, Mark.BREACHED, DUE.plusSeconds(86_400)))
                .isEqualTo(new Alert(null, Mark.BREACHED));
    }

    @Test
    @DisplayName("S8B-AC-04 a time equal to the due time is still a warning: nothing to tell after a warning")
    void s8bAc04_atDueTimeNothingAfterWarning() {
        assertThat(SlaAlerts.alert(RUNNING, Mark.WARNING, DUE)).isEqualTo(new Alert(null, Mark.WARNING));
        assertThat(SlaAlerts.alert(RUNNING, null, DUE)).isEqualTo(new Alert(Mark.WARNING, Mark.WARNING));
        // one instant after it, overdue
        assertThat(SlaAlerts.alert(RUNNING, Mark.WARNING, DUE.plusNanos(1)))
                .isEqualTo(new Alert(Mark.BREACHED, Mark.BREACHED));
    }

    @Test
    @DisplayName("S8B-AC-01 a goal in warning marked BREACHED is told nothing and keeps its mark")
    void s8bAc01_warningMarkedBreachedTellsNothing() {
        assertThat(SlaAlerts.alert(RUNNING, Mark.BREACHED, WARN)).isEqualTo(new Alert(null, Mark.BREACHED));
        assertThat(SlaAlerts.alert(RUNNING, Mark.BREACHED, DUE)).isEqualTo(new Alert(null, Mark.BREACHED));
    }

    @Test
    @DisplayName("S8B-AC-01 a goal before its warning is told nothing, and its mark is kept as it is")
    void s8bAc01_okTellsNothing() {
        for (Mark mark : marks()) {
            assertThat(SlaAlerts.alert(RUNNING, mark, T0)).as("%s", mark).isEqualTo(new Alert(null, mark));
            assertThat(SlaAlerts.alert(RUNNING, mark, WARN.minusNanos(1)))
                    .as("%s", mark)
                    .isEqualTo(new Alert(null, mark));
        }
    }

    @Test
    @DisplayName("S8B-AC-05 a stopped goal or one without a goal is never told, whatever the time and mark")
    void s8bAc05_stoppedAndNoGoalAreNeverTold() {
        for (Mark mark : marks()) {
            for (Instant now : List.of(T0, WARN, DUE, DUE.plusSeconds(1), DUE.plusSeconds(86_400))) {
                for (Clock clock : List.of(NO_GOAL, MET, MISSED, NONE, PAUSED)) {
                    assertThat(SlaAlerts.alert(clock, mark, now))
                            .as("%s %s %s", clock, mark, now)
                            .isEqualTo(new Alert(null, mark));
                }
            }
        }
    }

    // ---- lowered ----

    @Test
    @DisplayName("S8B-AC-08 a goal back to OK or without a goal has its mark lowered to null")
    void s8bAc08_okAndNoGoalLowerToNull() {
        for (Mark mark : marks()) {
            assertThat(SlaAlerts.lowered(RUNNING, mark, T0)).as("ok %s", mark).isNull();
            assertThat(SlaAlerts.lowered(RUNNING, mark, WARN.minusNanos(1))).as("ok edge %s", mark).isNull();
            assertThat(SlaAlerts.lowered(NO_GOAL, mark, DUE.plusSeconds(1))).as("no goal %s", mark).isNull();
        }
    }

    @Test
    @DisplayName("S8B-AC-08 a goal in warning marked BREACHED is lowered to WARNING; a lower mark is not raised")
    void s8bAc08_warningLowersBreachedOnly() {
        assertThat(SlaAlerts.lowered(RUNNING, Mark.BREACHED, WARN)).isEqualTo(Mark.WARNING);
        assertThat(SlaAlerts.lowered(RUNNING, Mark.BREACHED, DUE)).isEqualTo(Mark.WARNING);
        assertThat(SlaAlerts.lowered(RUNNING, Mark.WARNING, WARN)).isEqualTo(Mark.WARNING);
        assertThat(SlaAlerts.lowered(RUNNING, null, WARN)).isNull();
        assertThat(SlaAlerts.lowered(RUNNING, null, DUE)).isNull();
    }

    @Test
    @DisplayName("S8B-AC-08 an overdue goal keeps any mark: never raised, nothing later to lower from")
    void s8bAc08_breachedKeepsMark() {
        for (Mark mark : marks()) {
            assertThat(SlaAlerts.lowered(RUNNING, mark, DUE.plusNanos(1))).as("%s", mark).isEqualTo(mark);
            assertThat(SlaAlerts.lowered(RUNNING, mark, DUE.plusSeconds(86_400))).as("%s", mark).isEqualTo(mark);
        }
    }

    @Test
    @DisplayName("S8B-AC-04 the mark of a stopped clock, settled or paused in the trash, is kept")
    void s8bAc04_stoppedKeepsMark() {
        for (Mark mark : marks()) {
            for (Clock clock : List.of(PAUSED, MET, MISSED, NONE)) {
                assertThat(SlaAlerts.lowered(clock, mark, T0)).as("%s %s", clock, mark).isEqualTo(mark);
                assertThat(SlaAlerts.lowered(clock, mark, DUE.plusSeconds(1))).as("%s %s", clock, mark).isEqualTo(mark);
            }
        }
    }

    @Test
    @DisplayName("design 9절 a goal used up when it runs on again (reopen, restore) keeps its BREACHED mark")
    void loweredUsedUpWhenRunOnKeepsBreached() {
        Clock reopened = new Clock(3600, T0, null, T0, T0);
        assertThat(SlaAlerts.lowered(reopened, Mark.BREACHED, T0)).isEqualTo(Mark.BREACHED);
        assertThat(SlaAlerts.alert(reopened, Mark.BREACHED, T0)).isEqualTo(new Alert(null, Mark.BREACHED));
    }

    // ---- recalculated: the cases the class comment lists ----

    @Test
    @DisplayName("S8B-AC-12 first response OK to BREACHED and resolution BREACHED to OK at once: nothing counted")
    void s8bAc12_bothGoalsSwapSteps() {
        // the issue's step is BREACHED before and after: no change, no mark raised, the resolution's lowered
        assertThat(SlaAlerts.recalculated(OK_NOW, BREACHED_NOW, BREACHED_NOW, OK_NOW, null, Mark.BREACHED, NOW))
                .isEqualTo(new Recalculated(null, null, null));
        assertThat(SlaAlerts.recalculated(OK_NOW, BREACHED_NOW, BREACHED_NOW, OK_NOW, null, Mark.WARNING, NOW))
                .isEqualTo(new Recalculated(null, null, null));
    }

    @Test
    @DisplayName("S8B-AC-13 first response told overdue, resolution OK to WARNING: not counted, not raised")
    void s8bAc13_resolutionWarnsUnderBreachedFirstResponse() {
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, OK_NOW, BREACHED_NOW, WARNING_NOW, Mark.BREACHED, null, NOW))
                .isEqualTo(new Recalculated(null, Mark.BREACHED, null));
        // the resolution is then told one by one by the alert step
        assertThat(SlaAlerts.alert(WARNING_NOW, null, NOW)).isEqualTo(new Alert(Mark.WARNING, Mark.WARNING));
    }

    @Test
    @DisplayName("S8B-AC-13 an issue counted as released whose other goal went later: that goal is not raised")
    void s8bAc13_releasedIssueDoesNotRaiseOtherGoal() {
        // before: first response BREACHED, resolution OK; after: first response OK, resolution WARNING
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, OK_NOW, OK_NOW, WARNING_NOW, Mark.BREACHED, null, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, null, null));
        // from BREACHED to WARNING on the issue: still released; the goal that went later keeps no mark
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, NO_GOAL_NOW, WARNING_NOW, WARNING_NOW, Mark.BREACHED, null, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, Mark.WARNING, null));
    }

    @Test
    @DisplayName("S8B-AC-12 a BREACHED mark of a goal that lost its goal is lowered to null, and the issue released")
    void s8bAc12_goalLostLowersBreachedMark() {
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, BREACHED_NOW, NO_GOAL_NOW, NO_GOAL_NOW, Mark.BREACHED, Mark.BREACHED, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, null, null));
        // only one goal lost, the other still overdue: the issue's step stays
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, BREACHED_NOW, NO_GOAL_NOW, BREACHED_NOW, Mark.BREACHED, Mark.BREACHED, NOW))
                .isEqualTo(new Recalculated(null, null, Mark.BREACHED));
        // a goal lost from WARNING: released
        assertThat(SlaAlerts.recalculated(WARNING_NOW, OK_NOW, NO_GOAL_NOW, OK_NOW, Mark.WARNING, null, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, null, null));
    }

    @Test
    @DisplayName("S8B-AC-04 the marks of clocks paused in the trash are kept and nothing is counted")
    void s8bAc04_pausedClocksKeepMarks() {
        Clock pausedOther = new Clock(4000, null, null, null, null);
        assertThat(SlaAlerts.recalculated(
                        PAUSED, pausedOther, PAUSED, pausedOther, Mark.BREACHED, Mark.WARNING, NOW))
                .isEqualTo(new Recalculated(null, Mark.BREACHED, Mark.WARNING));
        assertThat(SlaAlerts.recalculated(PAUSED, pausedOther, PAUSED, pausedOther, null, null, NOW))
                .isEqualTo(new Recalculated(null, null, null));
    }

    // ---- recalculated: each change ----

    @Test
    @DisplayName("S8B-AC-12 BECAME_BREACHED: the goal that went overdue is marked BREACHED")
    void s8bAc12_becameBreached() {
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, BREACHED_NOW, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, null));
        // from no goal, which counts as OK
        assertThat(SlaAlerts.recalculated(NO_GOAL_NOW, NO_GOAL_NOW, NO_GOAL_NOW, BREACHED_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, null, Mark.BREACHED));
        // from WARNING: the issue was not overdue before
        assertThat(SlaAlerts.recalculated(WARNING_NOW, OK_NOW, BREACHED_NOW, OK_NOW, Mark.WARNING, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, null));
    }

    @Test
    @DisplayName("S8B-AC-13 BECAME_BREACHED raises every goal whose step went later, and only those")
    void s8bAc13_becameBreachedRaisesGoalsThatWentLater() {
        // both went later: first response to BREACHED, resolution to WARNING
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, BREACHED_NOW, WARNING_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, Mark.WARNING));
        // the resolution stayed in warning without a mark: not raised, the alert step tells it
        assertThat(SlaAlerts.recalculated(
                        WARNING_NOW, WARNING_NOW, BREACHED_NOW, WARNING_NOW, Mark.WARNING, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, null));
    }

    @Test
    @DisplayName("S8B-AC-12 BECAME_WARNING: from OK or no goal to warning, the goal marked WARNING")
    void s8bAc12_becameWarning() {
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, OK_NOW, WARNING_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_WARNING, null, Mark.WARNING));
        assertThat(SlaAlerts.recalculated(NO_GOAL_NOW, OK_NOW, WARNING_NOW, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_WARNING, Mark.WARNING, null));
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, WARNING_NOW, WARNING_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_WARNING, Mark.WARNING, Mark.WARNING));
    }

    @Test
    @DisplayName("S8B-AC-13 a mark later already is not raised past it, then lowered to the step after")
    void s8bAc13_raisedKeepsLaterMarkThenLowers() {
        // resolution OK to WARNING with a stale BREACHED mark: stays at most the step after, WARNING
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, OK_NOW, WARNING_NOW, null, Mark.BREACHED, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_WARNING, null, Mark.WARNING));
        // first response WARNING to BREACHED already marked BREACHED: stays BREACHED
        assertThat(SlaAlerts.recalculated(
                        WARNING_NOW, OK_NOW, BREACHED_NOW, OK_NOW, Mark.BREACHED, Mark.WARNING, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, null));
    }

    @Test
    @DisplayName("S8B-AC-12 RELEASED: the issue's step after is earlier than before; marks lowered to the step after")
    void s8bAc12_released() {
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, OK_NOW, WARNING_NOW, OK_NOW, Mark.BREACHED, null, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, Mark.WARNING, null));
        assertThat(SlaAlerts.recalculated(
                        WARNING_NOW, WARNING_NOW, OK_NOW, OK_NOW, Mark.WARNING, Mark.WARNING, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, null, null));
        assertThat(SlaAlerts.recalculated(BREACHED_NOW, OK_NOW, OK_NOW, OK_NOW, Mark.BREACHED, null, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, null, null));
    }

    @Test
    @DisplayName("S8B-AC-12 the issue's step unchanged: no change, no mark raised, marks lowered")
    void s8bAc12_noChange() {
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, OK_NOW, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(null, null, null));
        // OK and no goal count alike
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, NO_GOAL_NOW, NO_GOAL_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(null, null, null));
        assertThat(SlaAlerts.recalculated(NO_GOAL_NOW, NO_GOAL_NOW, OK_NOW, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(null, null, null));
        assertThat(SlaAlerts.recalculated(
                        WARNING_NOW, WARNING_NOW, WARNING_NOW, WARNING_NOW, Mark.WARNING, null, NOW))
                .isEqualTo(new Recalculated(null, Mark.WARNING, null));
        // still in warning on the issue, one goal moved from WARNING to OK and the other from OK to WARNING
        assertThat(SlaAlerts.recalculated(
                        WARNING_NOW, OK_NOW, OK_NOW, WARNING_NOW, Mark.WARNING, null, NOW))
                .isEqualTo(new Recalculated(null, null, null));
        assertThat(SlaAlerts.recalculated(
                        BREACHED_NOW, BREACHED_NOW, BREACHED_NOW, BREACHED_NOW, Mark.BREACHED, Mark.BREACHED, NOW))
                .isEqualTo(new Recalculated(null, Mark.BREACHED, Mark.BREACHED));
    }

    @Test
    @DisplayName("S8B-AC-12 a settled first response has no step: the issue's step is the resolution's, its mark kept")
    void s8bAc12_oneGoalStopped() {
        assertThat(SlaAlerts.recalculated(MET, OK_NOW, MET, WARNING_NOW, Mark.BREACHED, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_WARNING, Mark.BREACHED, Mark.WARNING));
        assertThat(SlaAlerts.recalculated(MISSED, BREACHED_NOW, MISSED, OK_NOW, Mark.BREACHED, Mark.BREACHED, NOW))
                .isEqualTo(new Recalculated(Change.RELEASED, Mark.BREACHED, null));
        assertThat(SlaAlerts.recalculated(NONE, OK_NOW, NONE, BREACHED_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, null, Mark.BREACHED));
        assertThat(SlaAlerts.recalculated(MET, WARNING_NOW, MET, WARNING_NOW, Mark.WARNING, null, NOW))
                .isEqualTo(new Recalculated(null, Mark.WARNING, null));
    }

    @Test
    @DisplayName("S8B-AC-12 both goals stopped: the issue has no step and is never counted; marks kept")
    void s8bAc12_bothStopped() {
        for (Mark fr : marks()) {
            for (Mark res : marks()) {
                assertThat(SlaAlerts.recalculated(MET, MISSED, MET, MISSED, fr, res, NOW))
                        .as("%s %s", fr, res)
                        .isEqualTo(new Recalculated(null, fr, res));
                assertThat(SlaAlerts.recalculated(MISSED, NONE, MISSED, NONE, fr, res, NOW))
                        .as("%s %s", fr, res)
                        .isEqualTo(new Recalculated(null, fr, res));
                assertThat(SlaAlerts.recalculated(PAUSED, MET, PAUSED, MET, fr, res, NOW))
                        .as("%s %s", fr, res)
                        .isEqualTo(new Recalculated(null, fr, res));
            }
        }
    }

    @Test
    @DisplayName("S8B-AC-12 judged at now: at the new due time itself the goal is in warning, an instant after overdue")
    void s8bAc12_judgedAtDueEdge() {
        Clock dueAtNow = new Clock(0, T0, null, at(4000), NOW);
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, dueAtNow, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_WARNING, Mark.WARNING, null));
        Clock dueJustBefore = new Clock(0, T0, null, at(4000), NOW.minusNanos(1));
        assertThat(SlaAlerts.recalculated(OK_NOW, OK_NOW, dueJustBefore, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, null));
        // a goal used up when it runs on with the new version: overdue at since
        Clock usedUp = new Clock(3600, NOW, null, NOW, NOW);
        Clock before = new Clock(3600, NOW, null, at(8000), at(10_000));
        assertThat(SlaAlerts.recalculated(before, OK_NOW, usedUp, OK_NOW, null, null, NOW))
                .isEqualTo(new Recalculated(Change.BECAME_BREACHED, Mark.BREACHED, null));
    }

    // ---- settledAt ----

    @Test
    @DisplayName("S8B-AC-23 a write that settles the first response as MET or MISSED keeps its time")
    void s8bAc23_settlingKeepsTheTime() {
        assertThat(SlaAlerts.settledAt(null, Outcome.MET, null, at(700))).isEqualTo(at(700));
        assertThat(SlaAlerts.settledAt(null, Outcome.MISSED, null, at(9000))).isEqualTo(at(9000));
    }

    @Test
    @DisplayName("S8B-AC-23 settled without a goal (NONE) or still not settled: no time")
    void s8bAc23_noneAndUnsettledHaveNoTime() {
        assertThat(SlaAlerts.settledAt(null, Outcome.NONE, null, at(700))).isNull();
        assertThat(SlaAlerts.settledAt(null, null, null, at(700))).isNull();
        assertThat(SlaAlerts.settledAt(Outcome.NONE, Outcome.NONE, null, at(700))).isNull();
    }

    @Test
    @DisplayName("S8B-AC-23 a time kept once never changes")
    void s8bAc23_keptTimeNeverChanges() {
        Instant kept = at(300);
        for (Outcome before : Arrays.asList(null, Outcome.MET, Outcome.MISSED, Outcome.NONE)) {
            for (Outcome after : Arrays.asList(null, Outcome.MET, Outcome.MISSED, Outcome.NONE)) {
                assertThat(SlaAlerts.settledAt(before, after, kept, at(9000)))
                        .as("%s -> %s", before, after)
                        .isEqualTo(kept);
            }
        }
    }
}
