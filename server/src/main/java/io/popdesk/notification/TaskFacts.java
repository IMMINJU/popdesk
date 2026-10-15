package io.popdesk.notification;

import java.util.Optional;

/**
 * What the send loop asks about a task just before it sends an email about it (MAIL-4, S6B-AC-34):
 * whether the task is still there and the recipient still its assignee, and its title now. Helpdesk
 * owns the tasks and implements this.
 */
public interface TaskFacts {

    /**
     * The task's title as it is now, when the task exists, is not deleted, and has this account as its
     * assignee; empty otherwise.
     */
    Optional<String> titleForAssignee(long taskId, long accountId);
}
