package io.popdesk.helpdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.popdesk.helpdesk.trash.RetentionSchedule.Decision;
import io.popdesk.helpdesk.trash.RetentionSchedule.State;
import io.popdesk.helpdesk.trash.RetentionSchedule.Step;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a run of automatic issue cleanup does (PII-6, S10B-AC-19, S10B-AC-20; acceptance.md 5절 5; design.md 0절
 * 7): the first run after it is turned on or made shorter moves nothing and starts a 24-hour wait; a period as
 * long or longer starts no new wait (periods compared by length, {@code P90D} equals {@code PT2160H}); turning it
 * off forgets the wait; after the wait, issues move at most once every 24 hours from the last run that moved one.
 */
class RetentionScheduleTest {

    private static final Duration P90D = Duration.parse("P90D");
    private static final Duration P100D = Duration.parse("P100D");
    private static final long P90D_SECONDS = 90L * 24 * 3600;
    private static final long P100D_SECONDS = 100L * 24 * 3600;
    private static final Duration HOURS_24 = Duration.ofHours(24);
    private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00.123456Z");
    private static final Instant EARLIER = NOW.minus(Duration.ofDays(3));
    private static final State NEVER = new State(null, null, null);

    @Test
    @DisplayName("PII-6 the wait and the time between two moving runs are both 24 hours")
    void constants() {
        assertThat(RetentionSchedule.WAIT).isEqualTo(HOURS_24);
        assertThat(RetentionSchedule.DAY).isEqualTo(HOURS_24);
    }

    @Test
    @DisplayName("PII-6 off and never on: OFF, nothing kept")
    void offNeverOn() {
        Decision d = RetentionSchedule.decide(NEVER, null, NOW);

        assertThat(d.step()).isEqualTo(Step.OFF);
        assertThat(d.next()).isEqualTo(NEVER);
    }

    @Test
    @DisplayName("S10B-AC-19 turned off: OFF, the period seen and the wait are forgotten, the last move stays")
    void offForgetsWait() {
        State last = new State(P90D_SECONDS, NOW.plus(Duration.ofHours(5)), EARLIER);

        Decision d = RetentionSchedule.decide(last, null, NOW);

        assertThat(d.step()).isEqualTo(Step.OFF);
        assertThat(d.next()).isEqualTo(new State(null, null, EARLIER));
    }

    @Test
    @DisplayName("S10B-AC-19 the first run after it is turned on: ARM, nothing moves, moves start 24 hours later")
    void firstOn() {
        Decision d = RetentionSchedule.decide(NEVER, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.ARM);
        assertThat(d.next()).isEqualTo(new State(P90D_SECONDS, NOW.plus(HOURS_24), null));
    }

    @Test
    @DisplayName("S10B-AC-19 off, then on again: a first day again, ARM, the last move stays")
    void offThenOnAgain() {
        State on = new State(P90D_SECONDS, null, EARLIER);
        State off = RetentionSchedule.decide(on, null, NOW).next();

        Decision d = RetentionSchedule.decide(off, P90D, NOW.plus(Duration.ofHours(1)));

        assertThat(d.step()).isEqualTo(Step.ARM);
        assertThat(d.next()).isEqualTo(
                new State(P90D_SECONDS, NOW.plus(Duration.ofHours(1)).plus(HOURS_24), EARLIER));
    }

    @Test
    @DisplayName("S10B-AC-19 made shorter: ARM with a new 24-hour wait from now, the last move stays")
    void shorter() {
        State last = new State(P100D_SECONDS, null, EARLIER);

        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.ARM);
        assertThat(d.next()).isEqualTo(new State(P90D_SECONDS, NOW.plus(HOURS_24), EARLIER));
    }

    @Test
    @DisplayName("S10B-AC-19 made shorter by one second is shorter: ARM")
    void shorterByOneSecond() {
        State last = new State(P90D_SECONDS + 1, null, null);

        assertThat(RetentionSchedule.decide(last, P90D, NOW).step()).isEqualTo(Step.ARM);
    }

    @Test
    @DisplayName("S10B-AC-19 made shorter again while waiting: the wait starts again from now")
    void shorterWhileWaiting() {
        State last = new State(P100D_SECONDS, NOW.plus(Duration.ofHours(3)), null);

        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.ARM);
        assertThat(d.next().movesFrom()).isEqualTo(NOW.plus(HOURS_24));
    }

    @ParameterizedTest
    @ValueSource(strings = {"P90D", "PT2160H", "P89DT24H"})
    @DisplayName("S10B-AC-19 the same length written another way is not shorter: no new wait")
    void sameLength(String setting) {
        State last = new State(P90D_SECONDS, null, null);

        Decision d = RetentionSchedule.decide(last, Duration.parse(setting), NOW);

        assertThat(d.step()).isEqualTo(Step.MOVE);
        assertThat(d.next()).isEqualTo(new State(P90D_SECONDS, null, NOW));
    }

    @Test
    @DisplayName("S10B-AC-19 made longer while waiting: the wait stays as it was, the longer period is kept")
    void longerKeepsWait() {
        Instant movesFrom = NOW.plus(Duration.ofHours(3));
        State last = new State(P90D_SECONDS, movesFrom, null);

        Decision d = RetentionSchedule.decide(last, P100D, NOW);

        assertThat(d.step()).isEqualTo(Step.WAIT);
        assertThat(d.next()).isEqualTo(new State(P100D_SECONDS, movesFrom, null));
    }

    @Test
    @DisplayName("S10B-AC-19 made longer, then back to the old value: shorter than the period seen last, ARM")
    void longerThenBack() {
        State longer = RetentionSchedule.decide(new State(P90D_SECONDS, null, EARLIER), P100D, NOW).next();

        Decision d = RetentionSchedule.decide(longer, P90D, NOW.plus(Duration.ofHours(1)));

        assertThat(d.step()).isEqualTo(Step.ARM);
    }

    @Test
    @DisplayName("S10B-AC-19 within the wait: WAIT, nothing changes but the period")
    void waiting() {
        Instant movesFrom = NOW.plus(MICRO);
        State last = new State(P90D_SECONDS, movesFrom, EARLIER);

        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.WAIT);
        assertThat(d.next()).isEqualTo(last);
    }

    @Test
    @DisplayName("S10B-AC-19 the wait wins over the day: within both, WAIT")
    void waitBeforeIdle() {
        State last = new State(P90D_SECONDS, NOW.plus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));

        assertThat(RetentionSchedule.decide(last, P90D, NOW).step()).isEqualTo(Step.WAIT);
    }

    @Test
    @DisplayName("S10B-AC-19 24 hours after ARM, at the end of the wait: MOVE, the wait stays on the row")
    void waitEnds() {
        Instant movesFrom = NOW;
        State last = new State(P90D_SECONDS, movesFrom, null);

        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.MOVE);
        assertThat(d.next()).isEqualTo(new State(P90D_SECONDS, movesFrom, NOW));
    }

    @Test
    @DisplayName("S10B-AC-20 within 24 hours of the last move: IDLE, nothing changes")
    void idle() {
        State last = new State(P90D_SECONDS, EARLIER, NOW.minus(HOURS_24).plus(MICRO));

        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.IDLE);
        assertThat(d.next()).isEqualTo(last);
    }

    @Test
    @DisplayName("S10B-AC-20 IDLE keeps a longer period set now")
    void idleKeepsLongerPeriod() {
        State last = new State(P90D_SECONDS, null, NOW.minus(Duration.ofHours(1)));

        Decision d = RetentionSchedule.decide(last, P100D, NOW);

        assertThat(d.step()).isEqualTo(Step.IDLE);
        assertThat(d.next()).isEqualTo(new State(P100D_SECONDS, null, NOW.minus(Duration.ofHours(1))));
    }

    @Test
    @DisplayName("S10B-AC-20 exactly 24 hours after the last move: MOVE, now is the last move")
    void dayEnds() {
        State last = new State(P90D_SECONDS, EARLIER, NOW.minus(HOURS_24));

        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(d.step()).isEqualTo(Step.MOVE);
        assertThat(d.next()).isEqualTo(new State(P90D_SECONDS, EARLIER, NOW));
    }

    @Test
    @DisplayName("S10B-AC-20 a longer period after the day: MOVE with the longer period")
    void moveWithLongerPeriod() {
        State last = new State(P90D_SECONDS, null, EARLIER);

        Decision d = RetentionSchedule.decide(last, P100D, NOW);

        assertThat(d.step()).isEqualTo(Step.MOVE);
        assertThat(d.next()).isEqualTo(new State(P100D_SECONDS, null, NOW));
    }

    @Test
    @DisplayName("PII-6 last and now are required")
    void nulls() {
        assertThatThrownBy(() -> RetentionSchedule.decide(null, P90D, NOW))
                .isInstanceOf(NullPointerException.class).hasMessage("last");
        assertThatThrownBy(() -> RetentionSchedule.decide(NEVER, P90D, null))
                .isInstanceOf(NullPointerException.class).hasMessage("now");
    }

    @Test
    @DisplayName("S10B-AC-20 a run that moved issues is the last move")
    void afterMovesSome() {
        State last = new State(P90D_SECONDS, EARLIER, EARLIER);
        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(RetentionSchedule.afterMoves(last, d, 1)).isEqualTo(new State(P90D_SECONDS, EARLIER, NOW));
        assertThat(RetentionSchedule.afterMoves(last, d, 250)).isEqualTo(d.next());
    }

    @Test
    @DisplayName("S10B-AC-20 a run that moved nothing keeps the earlier last move and the new period")
    void afterMovesNone() {
        State last = new State(P90D_SECONDS, EARLIER, EARLIER);
        Decision d = RetentionSchedule.decide(last, P100D, NOW);

        assertThat(RetentionSchedule.afterMoves(last, d, 0)).isEqualTo(new State(P100D_SECONDS, EARLIER, EARLIER));
    }

    @Test
    @DisplayName("S10B-AC-20 the first MOVE that moves nothing keeps no last move")
    void afterMovesNoneFirst() {
        State last = new State(P90D_SECONDS, EARLIER, null);
        Decision d = RetentionSchedule.decide(last, P90D, NOW);

        assertThat(RetentionSchedule.afterMoves(last, d, 0)).isEqualTo(new State(P90D_SECONDS, EARLIER, null));
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = "MOVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PII-6 only a MOVE has moves")
    void afterMovesOnlyMove(Step step) {
        Decision d = new Decision(step, new State(P90D_SECONDS, null, null));

        assertThatThrownBy(() -> RetentionSchedule.afterMoves(NEVER, d, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(step.name());
    }

    @Test
    @DisplayName("S10B-AC-19, S10B-AC-20 hourly runs from the day it is turned on: ARM, WAIT for 24 hours, MOVE, "
            + "IDLE for 24 hours; after a run that moved nothing the next run moves")
    void hourlyRuns() {
        Duration hour = Duration.ofHours(1);
        Instant t = NOW;
        Decision d = RetentionSchedule.decide(NEVER, P90D, t);
        assertThat(d.step()).isEqualTo(Step.ARM);
        State state = d.next();

        for (int h = 1; h < 24; h++) {
            d = RetentionSchedule.decide(state, P90D, t.plus(hour.multipliedBy(h)));
            assertThat(d.step()).as("hour %d", h).isEqualTo(Step.WAIT);
            state = d.next();
        }

        t = NOW.plus(HOURS_24);
        d = RetentionSchedule.decide(state, P90D, t);
        assertThat(d.step()).isEqualTo(Step.MOVE);
        state = RetentionSchedule.afterMoves(state, d, 3);
        assertThat(state.lastMovedAt()).isEqualTo(t);

        for (int h = 1; h < 24; h++) {
            d = RetentionSchedule.decide(state, P90D, t.plus(hour.multipliedBy(h)));
            assertThat(d.step()).as("hour %d after the move", h).isEqualTo(Step.IDLE);
            state = d.next();
        }

        Instant second = t.plus(HOURS_24);
        d = RetentionSchedule.decide(state, P90D, second);
        assertThat(d.step()).isEqualTo(Step.MOVE);
        state = RetentionSchedule.afterMoves(state, d, 0);
        assertThat(state.lastMovedAt()).isEqualTo(t);

        d = RetentionSchedule.decide(state, P90D, second.plus(hour));
        assertThat(d.step()).isEqualTo(Step.MOVE);
    }
}
