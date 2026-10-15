package io.popdesk.helpdesk.task;

import io.popdesk.helpdesk.OpenTasks;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.task.TaskChanges.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The open tasks a person is the assignee of, counted and cleared when an admin takes an agent off a
 * company or suspends an account (ACCT-7, S6B-AC-75, S6B-AC-76). The count and the clearing use one
 * condition. A {@code DONE} task and a task of a {@code CLOSED} issue keep their assignee.
 */
@Service
class TaskAssigneeClearing implements OpenTasks {

    private final TaskRepository tasks;
    private final Clock clock;

    TaskAssigneeClearing(TaskRepository tasks, Clock clock) {
        this.tasks = tasks;
        this.clock = clock;
    }

    @Override
    public long count(Viewer viewer, long accountId, Long companyId) {
        return tasks.countOpenAssignments(viewer, accountId, companyId);
    }

    /**
     * Clears them in the caller's transaction, after its checks, by one conditional UPDATE that does not
     * lock the issue rows: a task changed by another request first is left to that request. Each cleared
     * task gets an {@code ASSIGNEE} change from the account to nobody by the admin; the 1,000 entries of
     * S6B-AC-23 do not hold this back. Nobody is told (S6B-AC-76).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int clear(long accountId, Long companyId, long actorId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Long> cleared = tasks.updateAssigneesCleared(accountId, companyId);
        for (long taskId : cleared) {
            tasks.insertChange(taskId, actorId, Field.ASSIGNEE.name(), Long.toString(accountId), null, now);
        }
        return cleared.size();
    }
}
