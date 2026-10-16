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
 *
 * <p>Slice 8b (design.md 0절 7, 13, 14): the step also decides the marks of what the alerts told and when the first
 * response was settled ({@link SlaAlerts}). A step that brings clocks of an older version up first compares them, as
 * the job's recalculation does: an issue that became overdue, due soon, or neither is counted in the open summary
 * row at once, which locks that row after the issue's (0절 8), and the goals that went to a later step are marked
 * so they are not told one by one (acceptance.md 5절 5). Then the marks are lowered to the steps the write leaves.
 * Call each step once per write, after the issue row is locked; a refused write rolls back the count with it.
 */
public final class SlaWrite {

    private static final Set<IssueStatus> OPEN = Set.of(IssueStatus.RECEIVED, IssueStatus.CONFIRMED,
            IssueStatus.IN_PROGRESS);

    private final SlaPolicies policies;
    private final SlaReportRepository reports;
    private final SlaVersion current;

    SlaWrite(SlaPolicies policies, SlaReportRepository reports, SlaVersion current) {
        this.policies = policies;
        this.reports = reports;
        this.current = current;
    }

    /**
     * The clocks of an issue filed at {@code at} (S8A-AC-10, S8A-AC-11): both run from then while SLA is on, also for
     * a priority without goals (acceptance.md 5절 3). Nothing is told or settled yet.
     *
     * @return null when SLA is off: the issue is not under SLA, now or later
     */
    public IssueClocks filed(Priority priority, Instant at) {
        if (!current.on()) {
            return null;
        }
        return finish(new IssueClocks(current.number(), Clock.startingAt(at), Clock.startingAt(at)),
                Clock.startingAt(at), Clock.startingAt(at), priority, at);
    }

    /**
     * The first public comment of staff at {@code at} (S8A-AC-13): the first response stops, if it still runs. A
     * first response that settles here is settled at {@code at}, the first response time itself.
     */
    public IssueClocks answered(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks now = caughtUp(clocks, priority, at);
        Goals goals = current.goals(priority);
        return finish(now, SlaClocks.stop(now.firstResponse(), at, goals.firstResponse(), current.calendar()),
                now.resolution(), priority, at);
    }

    /**
     * A change of status at {@code at} (S8A-AC-14, S8A-AC-15): from an open status to {@code RESOLVED} or
     * {@code CLOSED} both goals stop; from those back to an open status the resolution runs on. Nothing else
     * changes a clock.
     */
    public IssueClocks moved(IssueClocks clocks, Priority priority, IssueStatus from, IssueStatus to, Instant at) {
        IssueClocks now = caughtUp(clocks, priority, at);
        Goals goals = current.goals(priority);
        Clock firstResponse = now.firstResponse();
        Clock resolution = now.resolution();
        if (OPEN.contains(from) && !OPEN.contains(to)) {
            firstResponse = SlaClocks.stop(firstResponse, at, goals.firstResponse(), current.calendar());
            resolution = SlaClocks.stop(resolution, at, goals.resolution(), current.calendar());
        } else if (!OPEN.contains(from) && OPEN.contains(to)) {
            resolution = SlaClocks.reopen(resolution, at);
        }
        return finish(now, firstResponse, resolution, priority, at);
    }

    /**
     * Another priority at {@code at} (S8A-AC-17): the running goals count to the new priority's goals from the time
     * used. A goal that comes to a later step this way is not counted in a summary; the alert step tells it
     * (S8B-AC-10).
     */
    public IssueClocks reprioritized(IssueClocks clocks, Priority from, Priority to, Instant at) {
        IssueClocks now = caughtUp(clocks, from, at);
        return finish(now, now.firstResponse(), now.resolution(), to, at);
    }

    /** The issue goes to the trash at {@code at} (S8A-AC-16): the running goals pause, and their marks stay. */
    public IssueClocks deleted(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks now = caughtUp(clocks, priority, at);
        return finish(now, SlaClocks.pause(now.firstResponse(), at, current.calendar()),
                SlaClocks.pause(now.resolution(), at, current.calendar()), priority, at);
    }

    /** The issue comes back from the trash at {@code at} (S8A-AC-16): the goals that were paused run on. */
    public IssueClocks restored(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks now = caughtUp(clocks, priority, at);
        return finish(now, SlaClocks.resume(now.firstResponse(), at), SlaClocks.resume(now.resolution(), at), priority,
                at);
    }

    /**
     * The clocks brought to the current version without new due times ({@link SlaPolicies#caughtUp}). Clocks of an
     * older version are compared at {@code at} with the same clocks worked out with the current version, as the job
     * does, the change is counted in the open summary row, and the marks become those of the comparison.
     */
    private IssueClocks caughtUp(IssueClocks clocks, Priority priority, Instant at) {
        IssueClocks caught = policies.caughtUp(clocks, current);
        if (clocks.version() == current.number()) {
            return caught;
        }
        IssueClocks after = policies.recalculated(clocks, priority, current);
        SlaAlerts.Recalculated compared = SlaAlerts.recalculated(clocks.firstResponse(), clocks.resolution(),
                after.firstResponse(), after.resolution(), clocks.firstResponseAlerted(), clocks.resolutionAlerted(),
                at);
        reports.add(SlaReportRepository.Counts.NONE.plus(compared.change()), at);
        return caught.marked(compared.firstResponse(), compared.resolution());
    }

    /**
     * The clocks to write: the running goals' due times worked out, the marks of {@code before} lowered to the steps
     * at {@code at}, and the first response's settling time.
     */
    private IssueClocks finish(IssueClocks before, Clock firstResponse, Clock resolution, Priority priority,
            Instant at) {
        Goals goals = current.goals(priority);
        BusinessCalendar calendar = current.calendar();
        Clock fr = SlaClocks.deadlines(firstResponse, goals.firstResponse(), calendar);
        Clock res = SlaClocks.deadlines(resolution, goals.resolution(), calendar);
        return new IssueClocks(current.number(), fr, res,
                SlaAlerts.lowered(fr, before.firstResponseAlerted(), at),
                SlaAlerts.lowered(res, before.resolutionAlerted(), at),
                SlaAlerts.settledAt(before.firstResponse().outcome(), fr.outcome(), before.firstResponseSettledAt(), at));
    }
}
