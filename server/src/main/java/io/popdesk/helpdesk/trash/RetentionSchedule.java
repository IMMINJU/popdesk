package io.popdesk.helpdesk.trash;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a run of automatic issue cleanup does (PII-6, S10B-AC-19, S10B-AC-20, acceptance.md 5절 5): from what the
 * last run saw, the setting now, and the time, whether it moves issues, waits, or starts a wait, and what it leaves
 * for the next run. Pure: no clock, no database.
 *
 * <ul>
 *   <li>Off: nothing moves, and the period seen and the wait are forgotten, so turning it on again is a first day.
 *   <li>On for the first time, or shorter than the period seen last: {@link Step#ARM}, the moves start
 *       {@link #WAIT} later. Periods are compared by length, so {@code P90D} and {@code PT2160H} are the same.
 *   <li>As long or longer: the wait, if any, stays as it was; once it has passed, issues are moved at most once
 *       every {@link #DAY}, counted from the last run that moved.
 * </ul>
 */
final class RetentionSchedule {

    /** How long the cleanup waits after it was turned on or shortened. */
    static final Duration WAIT = Duration.ofHours(24);

    /** How far apart two runs that move issues are at least. */
    static final Duration DAY = Duration.ofHours(24);

    /** What a run does. */
    enum Step {
        /** The cleanup is off: nothing moves. */
        OFF,
        /** Turned on or shortened: nothing moves, every admin is told, and the moves start {@link #WAIT} later. */
        ARM,
        /** Within the wait of an earlier {@link #ARM}: nothing moves. */
        WAIT,
        /** Within a {@link #DAY} of the last run that moved: nothing moves. */
        IDLE,
        /** The due issues move to the trash. */
        MOVE
    }

    /**
     * What a run saw, as the state row keeps it.
     *
     * @param periodSeconds the period of the setting in seconds, positive; null while the cleanup was off
     * @param movesFrom no issue moves before this time; null for no wait
     * @param lastMovedAt the time of the last run that moved; null when none did
     */
    record State(Long periodSeconds, Instant movesFrom, Instant lastMovedAt) {}

    /**
     * The step of a run and the state it leaves.
     *
     * @param step what the run does
     * @param next what the state row holds after the run
     */
    record Decision(Step step, State next) {}

    private RetentionSchedule() {}

    /**
     * Decides a run.
     *
     * @param last what the last run left; never null (the row with all three null is a cleanup never seen on)
     * @param setting the period set now, positive; null when the cleanup is off
     * @param now the time of the run
     * @return {@link Step#OFF} with the period and the wait cleared when {@code setting} is null; {@link Step#ARM}
     *     with the new period and a wait until {@code now} plus {@link #WAIT} when the cleanup was off or the
     *     setting is shorter than the period seen; otherwise the new period with the wait kept and {@link Step#WAIT}
     *     before the wait ends, {@link Step#IDLE} within a {@link #DAY} of the last move, or {@link Step#MOVE} with
     *     {@code now} as the last move. {@code lastMovedAt} changes only on {@link Step#MOVE}
     */
    static Decision decide(State last, Duration setting, Instant now) {
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(now, "now");
        if (setting == null) {
            return new Decision(Step.OFF, new State(null, null, last.lastMovedAt()));
        }
        long seconds = setting.toSeconds();
        if (last.periodSeconds() == null || seconds < last.periodSeconds()) {
            return new Decision(Step.ARM, new State(seconds, now.plus(WAIT), last.lastMovedAt()));
        }
        State kept = new State(seconds, last.movesFrom(), last.lastMovedAt());
        if (last.movesFrom() != null && now.isBefore(last.movesFrom())) {
            return new Decision(Step.WAIT, kept);
        }
        if (last.lastMovedAt() != null && now.isBefore(last.lastMovedAt().plus(DAY))) {
            return new Decision(Step.IDLE, kept);
        }
        return new Decision(Step.MOVE, new State(seconds, last.movesFrom(), now));
    }
}
