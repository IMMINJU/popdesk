package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import io.popdesk.helpdesk.sla.SlaClocks.Outcome;
import java.time.Instant;

/**
 * What the SLA alerts decide for one issue (SLA-5, slice 8b design.md 3절): where a goal stands, what to tell of it,
 * how far to lower what was told, what a recalculation changed, and when the first response was settled. Pure: the
 * caller passes the clocks, the marks, and the time. Every write of clocks and the alert step of
 * {@code sla-recalculate} go through these, so a write and the job decide alike.
 *
 * <p>A goal's step exists only while its clock runs: with a due time {@link Stage#OK} before 80%,
 * {@link Stage#WARNING} from then, {@link Stage#BREACHED} after the due time, as {@link SlaClocks#state} judges it
 * (at the due time itself it is not overdue yet); without one {@link Stage#NO_GOAL}, which counts as {@link Stage#OK}.
 * A stopped clock, settled or paused in the trash, is {@link Stage#STOPPED} and keeps its mark.
 *
 * <p>Cases for unit tests (the comments are what such tests go by): a recalculation that takes the first response
 * from OK to BREACHED and the resolution from BREACHED to OK at once (the issue's step stays, nothing is counted,
 * no mark is raised, the resolution's is lowered); an issue told its first response is overdue whose resolution
 * goes from OK to WARNING (the step stays, the resolution's mark is not raised, so it is told one by one); an issue
 * counted as released whose other goal went to a later step (not raised, told one by one); a {@code BREACHED} mark
 * of a goal that lost its goal (lowered to null); the mark of a clock paused in the trash (kept); a time equal to the
 * due time (still {@link Stage#WARNING}, nothing to tell after a warning).
 */
public final class SlaAlerts {

    private SlaAlerts() {}

    /** What was last told of a goal; null when nothing was. Stored in {@code fr_alerted} and {@code res_alerted}. */
    public enum Mark {
        WARNING,
        BREACHED
    }

    /** Where a goal stands, in the order of its steps (slice 8b acceptance.md 2절 "목표의 단계"). */
    public enum Stage {
        /** Not running: settled, or paused in the trash. No step. */
        STOPPED,
        /** Running without a goal; counts as {@link #OK}. */
        NO_GOAL,
        OK,
        WARNING,
        BREACHED
    }

    /** How a recalculation changed an issue's step (S8B-AC-12). */
    public enum Change {
        BECAME_BREACHED,
        BECAME_WARNING,
        RELEASED
    }

    /**
     * What to tell of one goal now, and its mark after that.
     *
     * @param tell {@link Mark#WARNING} or {@link Mark#BREACHED} to tell, or null for nothing
     * @param mark the goal's mark from now on
     */
    public record Alert(Mark tell, Mark mark) {}

    /**
     * What a recalculation of one issue changed, and the marks of its two goals after it.
     *
     * @param change null when the issue's step did not change as S8B-AC-12 counts it
     */
    public record Recalculated(Change change, Mark firstResponse, Mark resolution) {}

    /**
     * Where the goal stands at {@code now}.
     *
     * @param clock any clock as stored, with its warning and due times worked out
     * @return {@link Stage#STOPPED} for a clock that does not run (settled, paused), {@link Stage#NO_GOAL} for a
     *     running one without a due time, {@link Stage#BREACHED} for one whose goal was used up when it ran on
     *     (its due time is its {@code since}), else {@link Stage#OK}, {@link Stage#WARNING}, or
     *     {@link Stage#BREACHED} as {@link SlaClocks#state} says: overdue only after the due time
     */
    public static Stage stage(Clock clock, Instant now) {
        if (!clock.running()) {
            return Stage.STOPPED;
        }
        if (clock.dueAt() == null) {
            return Stage.NO_GOAL;
        }
        if (!clock.dueAt().isAfter(clock.since())) {
            // The goal was used up when the clock ran on (a restore, a reopening): overdue at once. Only a goal used
            // exactly, to the second, is due at that instant and not yet overdue; a nanosecond later it is.
            return Stage.BREACHED;
        }
        return switch (SlaClocks.state(clock, now)) {
            case BREACHED -> Stage.BREACHED;
            case WARNING -> Stage.WARNING;
            default -> Stage.OK;
        };
    }

    /**
     * What the alert step tells of one goal at {@code now} (S8B-AC-01, S8B-AC-02, S8B-AC-04): an overdue goal not
     * marked {@link Mark#BREACHED} is told it is overdue, also when its warning was never told (one item, not two);
     * a goal in warning without a mark is told the warning. Nothing else is told, and a stopped goal or one without
     * a goal never is.
     *
     * @param mark the goal's mark, or null
     * @return the step to tell, or a null {@code tell}, and the mark after it: the step told, else {@code mark}
     */
    public static Alert alert(Clock clock, Mark mark, Instant now) {
        Stage stage = stage(clock, now);
        if (stage == Stage.BREACHED && mark != Mark.BREACHED) {
            return new Alert(Mark.BREACHED, Mark.BREACHED);
        }
        if (stage == Stage.WARNING && mark == null) {
            return new Alert(Mark.WARNING, Mark.WARNING);
        }
        return new Alert(null, mark);
    }

    /**
     * The mark lowered to the goal's step after a write of its clock (S8B-AC-08, slice 8b acceptance.md 5절 13), so
     * a step the goal comes to again is told again. Never raises a mark.
     *
     * @param clock the clock as the write leaves it
     * @param mark the mark before, or null
     * @return {@code mark} for a stopped clock (also in the trash) and for a goal at the step marked or later; null
     *     for a goal that is {@link Stage#OK} or has no goal; {@link Mark#WARNING} for a goal in warning marked
     *     {@link Mark#BREACHED}
     */
    public static Mark lowered(Clock clock, Mark mark, Instant now) {
        return switch (stage(clock, now)) {
            case STOPPED, BREACHED -> mark;
            case NO_GOAL, OK -> null;
            case WARNING -> mark == Mark.BREACHED ? Mark.WARNING : mark;
        };
    }

    /**
     * What working an issue's clocks out with a new policy version changed (S8B-AC-12, S8B-AC-13), judged at
     * {@code now} on the clocks right before and right after it. The issue's step is the later of its running goals'
     * steps, no goal counting as {@link Stage#OK}; an issue whose goals both stopped has none and is never counted.
     * {@link Change#BECAME_BREACHED} when it is overdue after and was not before; {@link Change#BECAME_WARNING} when
     * it is in warning after and was OK before; {@link Change#RELEASED} when its step after is earlier than before,
     * a goal lost included. For the first two, each goal whose step went later gets that step as its mark, so it is
     * not told one by one; with no change counted no mark is raised, and such a goal is told by the alert step. Then
     * every mark is {@link #lowered} to the goal's step after.
     *
     * @param firstResponseBefore the clock as stored, of the older version
     * @param firstResponseAfter the same clock worked out with the new version; it runs exactly when the one before
     *     does
     * @param firstResponseMark the first response goal's mark, or null
     * @return the change, null when none counts, and the two marks after
     */
    public static Recalculated recalculated(Clock firstResponseBefore, Clock resolutionBefore,
            Clock firstResponseAfter, Clock resolutionAfter, Mark firstResponseMark, Mark resolutionMark,
            Instant now) {
        Stage frBefore = stage(firstResponseBefore, now);
        Stage resBefore = stage(resolutionBefore, now);
        Stage frAfter = stage(firstResponseAfter, now);
        Stage resAfter = stage(resolutionAfter, now);
        int before = Math.max(rank(frBefore), rank(resBefore));
        int after = Math.max(rank(frAfter), rank(resAfter));
        Change change = null;
        if (before >= 0 && after >= 0) {
            if (after == rank(Stage.BREACHED) && before < after) {
                change = Change.BECAME_BREACHED;
            } else if (after == rank(Stage.WARNING) && before == rank(Stage.OK)) {
                change = Change.BECAME_WARNING;
            } else if (after < before) {
                change = Change.RELEASED;
            }
        }
        Mark fr = firstResponseMark;
        Mark res = resolutionMark;
        if (change == Change.BECAME_BREACHED || change == Change.BECAME_WARNING) {
            fr = raised(fr, frBefore, frAfter);
            res = raised(res, resBefore, resAfter);
        }
        return new Recalculated(change, lowered(firstResponseAfter, fr, now), lowered(resolutionAfter, res, now));
    }

    /**
     * When the first response goal was settled (S8B-AC-23, slice 8b design.md 0절 14), after a write that may settle
     * it.
     *
     * @param before the goal's outcome before the write, null while it was not settled
     * @param after its outcome after the write
     * @param settledAt the time kept before, or null
     * @param at the time of the write: for a response, the first response time itself
     * @return {@code settledAt} when one was kept, which never changes; {@code at} when the write settles the goal as
     *     {@code MET} or {@code MISSED}; else null ({@code NONE}, or still not settled)
     */
    public static Instant settledAt(Outcome before, Outcome after, Instant settledAt, Instant at) {
        if (settledAt != null) {
            return settledAt;
        }
        if (before == null && (after == Outcome.MET || after == Outcome.MISSED)) {
            return at;
        }
        return null;
    }

    /** The goal's step as a number: -1 stopped, 0 OK or no goal, 1 warning, 2 overdue. */
    private static int rank(Stage stage) {
        return switch (stage) {
            case STOPPED -> -1;
            case NO_GOAL, OK -> 0;
            case WARNING -> 1;
            case BREACHED -> 2;
        };
    }

    /** The mark of a goal whose step went later: that step, unless the mark is later already. */
    private static Mark raised(Mark mark, Stage before, Stage after) {
        if (rank(after) <= rank(before)) {
            return mark;
        }
        Mark step = switch (after) {
            case BREACHED -> Mark.BREACHED;
            case WARNING -> Mark.WARNING;
            default -> null;
        };
        if (step == null || mark == Mark.BREACHED) {
            return mark;
        }
        return step;
    }
}
