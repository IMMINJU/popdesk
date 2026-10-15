package io.popdesk.helpdesk;

import java.util.Map;

/**
 * The open issues and tasks a person is the assignee of (ACCT-7, S6B-AC-75), as the account and company
 * services need them: how many there are, and clearing them. The issue package implements it, so that
 * accounts and companies do not depend on issues (ADR D-57).
 */
public interface OpenAssignments {

    /**
     * How many of each a person is the assignee of.
     *
     * @param issues open issues
     * @param tasks open tasks of issues that are not {@code CLOSED}
     */
    record Counts(long issues, long tasks) {}

    /** How many of each one clearing cleared. */
    record Cleared(int issues, int tasks) {

        /**
         * Adds {@code clearedTasks} to the changes of the audit entry of the request, only when a task was
         * cleared (S6B-AC-76): an entry about issues alone stays as it was.
         */
        public void putTasks(Map<String, Object> changes) {
            if (tasks > 0) {
                changes.put("clearedTasks", tasks);
            }
        }
    }

    /**
     * How many open issues and tasks the account is the assignee of, among the issues the viewer, an
     * admin, sees.
     *
     * @param companyId that company only, or null for every company
     */
    Counts count(Viewer viewer, long accountId, Long companyId);

    /**
     * Clears the assignee of those issues and tasks in the caller's transaction; there must be one.
     *
     * @param companyId that company only, or null for every company
     * @param actorId the admin, for the history entry of each cleared issue and the change of each task
     */
    Cleared clear(long accountId, Long companyId, long actorId);
}
