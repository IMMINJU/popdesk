package io.popdesk.helpdesk.sla;

import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.sla.SlaClocks.Clock;
import io.popdesk.helpdesk.sla.SlaVersion.Goals;
import java.time.Instant;
import java.util.Set;

/**
 * One write that changes the SLA clocks of an issue, under the version that was current once its lock was held
 * ({@link SlaPolicies#begin}). Each step first brings the clocks to that version as the job would (design.md 0절 2),
 * then does its part, then works out the running goals' warning and due times again; a goal the step stops keeps
 * the times it had (design.md 2절). The caller writes what it gets
 * in the same UPDATE as the rest of the change (design.md 0절 8b), on the issue row it locked.
 */
public final class SlaWrite {

    private static final Set<IssueStatus> OPEN = Set.of(IssueStatus.RECEIVED, IssueStatus.CONFIRMED,
            IssueStatus.IN_PROGRESS);

    private final SlaPolicies policies;
    private final SlaVersion current;

    SlaWrite(SlaPolicies policies, SlaVersion current) {
        this.policies = policies;
        this.current = current;
    }

    /**
     * The clocks of an issue filed at {@code at} (S8A-AC-10, S8A-AC-11): both run from then while SLA is on, also for
     * a priority without goals (acceptance.md 5절 3).
     *
     * @return null when SLA is off: the issue is not under SLA, now or later
     */
    public IssueClocks filed(Priority priority, Instant at) {
        if (!current.on()) {
            return null;
        }
        return finish(Clock.startingAt(at), Clock.startingAt(at), priority);
    }

    /** The first public comment of staff at {@code at} (S8A-AC-13): the first response stops, if it still runs. */
    public IssueClocks answered(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks now = policies.caughtUp(clocks, current);
        Goals goals = current.goals(priority);
        return finish(SlaClocks.stop(now.firstResponse(), at, goals.firstResponse(), current.calendar()),
                now.resolution(), priority);
    }

    /**
     * A change of status at {@code at} (S8A-AC-14, S8A-AC-15): from an open status to {@code RESOLVED} or
     * {@code CLOSED} both goals stop; from those back to an open status the resolution runs on. Nothing else
     * changes a clock.
     */
    public IssueClocks moved(IssueClocks clocks, Priority priority, IssueStatus from, IssueStatus to, Instant at) {
        IssueClocks now = policies.caughtUp(clocks, current);
        Goals goals = current.goals(priority);
        Clock firstResponse = now.firstResponse();
        Clock resolution = now.resolution();
        if (OPEN.contains(from) && !OPEN.contains(to)) {
            firstResponse = SlaClocks.stop(firstResponse, at, goals.firstResponse(), current.calendar());
            resolution = SlaClocks.stop(resolution, at, goals.resolution(), current.calendar());
        } else if (!OPEN.contains(from) && OPEN.contains(to)) {
            resolution = SlaClocks.reopen(resolution, at);
        }
        return finish(firstResponse, resolution, priority);
    }

    /** Another priority (S8A-AC-17): the running goals count to the new priority's goals from the time used. */
    public IssueClocks reprioritized(IssueClocks clocks, Priority to) {
        IssueClocks now = policies.caughtUp(clocks, current);
        return finish(now.firstResponse(), now.resolution(), to);
    }

    /** The issue goes to the trash at {@code at} (S8A-AC-16): the running goals pause. */
    public IssueClocks deleted(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks now = policies.caughtUp(clocks, current);
        return finish(SlaClocks.pause(now.firstResponse(), at, current.calendar()),
                SlaClocks.pause(now.resolution(), at, current.calendar()), priority);
    }

    /** The issue comes back from the trash at {@code at} (S8A-AC-16): the goals that were paused run on. */
    public IssueClocks restored(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks now = policies.caughtUp(clocks, current);
        return finish(SlaClocks.resume(now.firstResponse(), at), SlaClocks.resume(now.resolution(), at), priority);
    }

    private IssueClocks finish(Clock firstResponse, Clock resolution, Priority priority) {
        Goals goals = current.goals(priority);
        BusinessCalendar calendar = current.calendar();
        return new IssueClocks(current.number(), SlaClocks.deadlines(firstResponse, goals.firstResponse(), calendar),
                SlaClocks.deadlines(resolution, goals.resolution(), calendar));
    }
}
