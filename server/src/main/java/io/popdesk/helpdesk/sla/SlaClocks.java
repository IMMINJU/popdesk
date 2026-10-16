package io.popdesk.helpdesk.sla;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The calculation of one SLA goal's clock (SLA-3 to SLA-5, design.md 2절). Every write that changes a clock goes
 * through these, so filing, a first response, a change of status or priority, the trash, and the job give the same
 * result in any order (S8A-AC-21). Pure: the caller passes the time, the goal, and the calendar.
 *
 * <p>A goal of {@code null} minutes is no goal. Times are kept to the second ({@link #truncate}).
 */
public final class SlaClocks {

    private SlaClocks() {}

    /** How a stopped clock was settled (S8A-AC-13, S8A-AC-15). */
    public enum Outcome {
        /** Stopped within the goal. */
        MET,
        /** Stopped after the goal ran out. */
        MISSED,
        /** Stopped without a goal. */
        NONE
    }

    /** Where a goal stands (S8A-AC-18). */
    public enum State {
        OK,
        WARNING,
        BREACHED,
        MET,
        MISSED
    }

    /**
     * One goal's clock (design.md 0절 1).
     *
     * @param spent business seconds counted before {@code since}, 0 or more
     * @param since the start of the stretch that runs now; null while the clock is stopped
     * @param outcome null while it runs or is paused; set once it stopped for good
     * @param warnAt when 80% of the goal is used, for a running clock with a goal, or as it was when it stopped;
     *     else null
     * @param dueAt when the goal runs out, likewise
     */
    public record Clock(long spent, Instant since, Outcome outcome, Instant warnAt, Instant dueAt) {

        /** A clock that starts now, without due times yet. */
        public static Clock startingAt(Instant at) {
            return new Clock(0, truncate(at), null, null, null);
        }

        public boolean running() {
            return since != null;
        }
    }

    /** A policy version as the calculation needs it: when it began to count, and its business time. */
    public record Stretch(Instant startsAt, BusinessCalendar calendar) {}

    /**
     * Brings a clock from the version it was worked out with to the latest one (S8A-AC-20, S8A-AC-23): for each later
     * version, the time up to its start counts with the version before it, and a running clock runs on from that
     * start. A stopped clock stays as it is. Due times are not touched; call {@link #deadlines} after.
     *
     * @param clock the clock as stored
     * @param versions the clock's version first, then every later version in order, each with its start; one item
     *     changes nothing
     * @return the clock with its {@code spent} and {@code since} as of the last version
     */
    public static Clock catchUp(Clock clock, List<Stretch> versions) {
        long spent = clock.spent();
        Instant since = clock.since();
        for (int i = 1; i < versions.size() && since != null; i++) {
            Instant start = versions.get(i).startsAt();
            if (since.isBefore(start)) {
                spent += versions.get(i - 1).calendar().seconds(since, start);
                since = start;
            }
        }
        return new Clock(spent, since, clock.outcome(), clock.warnAt(), clock.dueAt());
    }

    /**
     * Stops a running clock for good and settles it (S8A-AC-13 to S8A-AC-15).
     *
     * @param at when it stops, not before {@code since}
     * @param goalMinutes the goal of the issue's priority, or null
     * @return a stopped clock unchanged; else the time up to {@code at} added, {@code since} null, and
     *     {@link Outcome#NONE} without a goal, {@link Outcome#MET} when the time used is the goal or less,
     *     {@link Outcome#MISSED} when more. The time used counts whole seconds, so when it is the goal exactly,
     *     {@code at} decides: {@link Outcome#MET} only at the instant the goal ran out, {@link Outcome#MISSED} after
     *     it (within the second after it, or outside business hours after the goal ran out at a closing time), as
     *     {@link #state} calls that time {@link State#BREACHED}. The warning and due times stay
     */
    public static Clock stop(Clock clock, Instant at, Integer goalMinutes, BusinessCalendar calendar) {
        if (!clock.running()) {
            return clock;
        }
        long spent = clock.spent() + calendar.seconds(clock.since(), at);
        Outcome outcome;
        if (goalMinutes == null) {
            outcome = Outcome.NONE;
        } else if (spent == goalMinutes * 60L) {
            Instant ranOut = calendar.plus(clock.since(), goalMinutes * 60L - clock.spent());
            outcome = at.isAfter(ranOut) ? Outcome.MISSED : Outcome.MET;
        } else {
            outcome = spent < goalMinutes * 60L ? Outcome.MET : Outcome.MISSED;
        }
        return new Clock(spent, null, outcome, clock.warnAt(), clock.dueAt());
    }

    /**
     * Pauses a running clock without settling it, for the trash (S8A-AC-16).
     *
     * @param at when it pauses, not before {@code since}
     * @return a stopped clock unchanged; else the time up to {@code at} added, {@code since} null, the outcome
     *     still null, and no warning or due time
     */
    public static Clock pause(Clock clock, Instant at, BusinessCalendar calendar) {
        if (!clock.running()) {
            return clock;
        }
        return new Clock(clock.spent() + calendar.seconds(clock.since(), at), null, null, null, null);
    }

    /**
     * Runs a paused clock on, for a restore (S8A-AC-16): only one that was running when it went to the trash, that
     * is, stopped without an outcome. Due times are not worked out; call {@link #deadlines} after.
     *
     * @return the clock running from {@code at}; a running or a settled clock unchanged
     */
    public static Clock resume(Clock clock, Instant at) {
        if (clock.running() || clock.outcome() != null) {
            return clock;
        }
        return new Clock(clock.spent(), truncate(at), null, null, null);
    }

    /**
     * Runs a stopped resolution clock on when its issue is open again (S8A-AC-15): the outcome is cleared and the time
     * counted before stays. Due times are not worked out; call {@link #deadlines} after.
     *
     * @return the clock running from {@code at}; a running clock unchanged
     */
    public static Clock reopen(Clock clock, Instant at) {
        if (clock.running()) {
            return clock;
        }
        return new Clock(clock.spent(), truncate(at), null, null, null);
    }

    /**
     * The warning and due times of a running clock (S8A-AC-10, S8A-AC-17, design.md 0절 7): the goal less the time
     * used, counted from {@code since}; 80% of the goal likewise. When that is 0 or less, the time is {@code since}.
     *
     * @param goalMinutes the goal of the issue's priority in the version of {@code calendar}, or null
     * @return a stopped clock unchanged; a running one without a goal with no warning or due time; else with both,
     *     to the second
     */
    public static Clock deadlines(Clock clock, Integer goalMinutes, BusinessCalendar calendar) {
        if (!clock.running()) {
            return clock;
        }
        if (goalMinutes == null) {
            return new Clock(clock.spent(), clock.since(), null, null, null);
        }
        long goal = goalMinutes * 60L;
        Instant due = calendar.plus(clock.since(), goal - clock.spent());
        Instant warn = calendar.plus(clock.since(), goalMinutes * 48L - clock.spent());
        return new Clock(clock.spent(), clock.since(), null, truncate(warn), truncate(due));
    }

    /**
     * Where a goal stands at {@code now} (S8A-AC-18).
     *
     * @return {@link State#MET} or {@link State#MISSED} once settled; null when settled without a goal, or when the
     *     clock has no due time (no goal, or paused); else {@link State#BREACHED} after the due time,
     *     {@link State#WARNING} at or after the warning time, {@link State#OK} before. At the due time itself the
     *     goal is not breached yet
     */
    public static State state(Clock clock, Instant now) {
        if (clock.outcome() != null) {
            return switch (clock.outcome()) {
                case MET -> State.MET;
                case MISSED -> State.MISSED;
                case NONE -> null;
            };
        }
        if (!clock.running() || clock.dueAt() == null) {
            return null;
        }
        if (now.isAfter(clock.dueAt())) {
            return State.BREACHED;
        }
        return now.isBefore(clock.warnAt()) ? State.OK : State.WARNING;
    }

    /**
     * The business minutes left of a running goal (S8A-AC-19).
     *
     * @param now not before {@code since}
     * @param goalMinutes the goal in the version of {@code calendar}, or null
     * @return null for a stopped clock or without a goal; else the goal less the business time used up to
     *     {@code now}, in whole minutes rounded down: positive minutes left, or after the goal ran out a negative
     *     number of minutes past it, rounded up in size. After the due time it is -1 or less, also while no business
     *     time has passed since then (outside business hours, or within the second after it), so it agrees with
     *     {@link State#BREACHED}
     */
    public static Long remainingMinutes(Clock clock, Instant now, Integer goalMinutes, BusinessCalendar calendar) {
        if (!clock.running() || goalMinutes == null) {
            return null;
        }
        long left = goalMinutes * 60L - clock.spent() - calendar.seconds(clock.since(), now);
        long minutes = Math.floorDiv(left, 60L);
        return clock.dueAt() != null && now.isAfter(clock.dueAt()) ? Math.min(minutes, -1L) : minutes;
    }

    /** An instant to the second, as clock times are kept (S8A-AC-19). */
    public static Instant truncate(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.SECONDS);
    }
}
