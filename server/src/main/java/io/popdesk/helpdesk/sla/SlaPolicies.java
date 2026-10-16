package io.popdesk.helpdesk.sla;

import io.popdesk.InstallationZone;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import io.popdesk.helpdesk.sla.SlaClocks.Outcome;
import io.popdesk.helpdesk.sla.SlaClocks.State;
import io.popdesk.helpdesk.sla.SlaClocks.Stretch;
import io.popdesk.helpdesk.sla.SlaVersion.Goals;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The versions of the SLA policy as the clocks of issues use them: the start of a write that changes clocks
 * ({@link #begin}), the job's calculation ({@link #recalculated}), and the goals of an issue as staff see them
 * ({@link #view}).
 *
 * <p>A version never changes once committed, so each is read from the database once and kept. A version is only
 * kept when read by number from a transaction that sees it committed: the change that makes a version never reads it
 * back.
 */
@Service
public class SlaPolicies {

    /** One goal of an issue as staff see it (S8A-AC-30); all three null without a goal. */
    public record GoalView(State state, Instant dueAt, Long remainingMinutes) {}

    /** The two goals of an issue under SLA. */
    public record SlaView(GoalView firstResponse, GoalView resolution) {}

    private final SlaPolicyRepository repository;
    private final SlaReportRepository reports;
    private final InstallationZone zone;
    private final Map<Integer, SlaVersion> versions = new ConcurrentHashMap<>();

    SlaPolicies(SlaPolicyRepository repository, SlaReportRepository reports, InstallationZone zone) {
        this.repository = repository;
        this.reports = reports;
        this.zone = zone;
    }

    /**
     * Starts a write of the clocks of one issue (design.md 0절 8, 8b): the current version's row
     * {@code FOR KEY SHARE}, so a policy change waits for this transaction and this one for a change that read the
     * time already, and then the version that is current once the lock is held. Call it before the issue row is
     * locked, in the write's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SlaWrite begin() {
        return new SlaWrite(this, reports, version(repository.lockCurrent("key share")));
    }

    /** Whether SLA is on now: the current version has a goal (S8B-AC-22). Not locked. */
    public boolean on() {
        return version(repository.current()).on();
    }

    /** The version with this number, which exists. */
    SlaVersion version(int number) {
        return versions.computeIfAbsent(number, n -> repository.find(n, zone.zone())
                .orElseThrow(() -> new IllegalStateException("No SLA policy version " + n)));
    }

    /** The versions from {@code from} to {@code to}, both included, in order, as the calculation needs them. */
    List<Stretch> stretches(int from, int to) {
        List<Stretch> out = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            SlaVersion version = version(n);
            out.add(new Stretch(version.startsAt(), version.calendar()));
        }
        return out;
    }

    /**
     * The clocks brought to {@code current} (S8A-AC-20): the time up to each later version's start counts with the
     * version before, and the running goals get the warning and due times of {@code current}'s goals of the
     * priority. Clocks of {@code current} already are given as they are. The marks and the settling time stay; the
     * caller decides them ({@link SlaAlerts#recalculated}).
     */
    IssueClocks recalculated(IssueClocks clocks, Priority priority, SlaVersion current) {
        if (clocks.version() == current.number()) {
            return clocks;
        }
        List<Stretch> stretches = stretches(clocks.version(), current.number());
        Goals goals = current.goals(priority);
        BusinessCalendar calendar = current.calendar();
        return new IssueClocks(current.number(),
                SlaClocks.deadlines(SlaClocks.catchUp(clocks.firstResponse(), stretches), goals.firstResponse(),
                        calendar),
                SlaClocks.deadlines(SlaClocks.catchUp(clocks.resolution(), stretches), goals.resolution(), calendar),
                clocks.firstResponseAlerted(), clocks.resolutionAlerted(), clocks.firstResponseSettledAt());
    }

    /**
     * The clocks brought to {@code current} without new warning and due times (design.md 2절 {@code catchUp}): a
     * write does its part after this, and a clock it stops keeps the times it had.
     */
    IssueClocks caughtUp(IssueClocks clocks, SlaVersion current) {
        if (clocks.version() == current.number()) {
            return clocks;
        }
        List<Stretch> stretches = stretches(clocks.version(), current.number());
        return new IssueClocks(current.number(), SlaClocks.catchUp(clocks.firstResponse(), stretches),
                SlaClocks.catchUp(clocks.resolution(), stretches), clocks.firstResponseAlerted(),
                clocks.resolutionAlerted(), clocks.firstResponseSettledAt());
    }

    /**
     * The goals of an issue under SLA at {@code now} (S8A-AC-18, S8A-AC-19, S8A-AC-30), judged on the version the
     * clocks were worked out with.
     */
    public SlaView view(IssueClocks clocks, Priority priority, Instant now) {
        SlaVersion version = version(clocks.version());
        Goals goals = version.goals(priority);
        BusinessCalendar calendar = version.calendar();
        return new SlaView(goal(clocks.firstResponse(), goals.firstResponse(), calendar, now),
                goal(clocks.resolution(), goals.resolution(), calendar, now));
    }

    private static GoalView goal(Clock clock, Integer minutes, BusinessCalendar calendar, Instant now) {
        Instant due = clock.outcome() == Outcome.NONE ? null : clock.dueAt();
        State state = SlaClocks.state(clock, now);
        if (state == State.WARNING && minutes != null && clock.running() && clock.spent() > minutes * 60L) {
            // Ran on with more than the goal used (a restore, a reopening): overdue from that instant, as stopping it
            // then settles it MISSED (slice 8b, S8B-AC-04).
            state = State.BREACHED;
        }
        return new GoalView(state, due,
                due == null ? null : SlaClocks.remainingMinutes(clock, now, minutes, calendar));
    }
}
