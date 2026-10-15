package io.popdesk.helpdesk;

/**
 * The open tasks a person is the assignee of (ACCT-7, S6B-AC-75, S6B-AC-76): those {@code OPEN}, not
 * deleted, of issues that are not {@code CLOSED}. The task package implements it, so that the issue
 * package, which clears an account's assignments, does not depend on tasks (ADR D-57).
 */
public interface OpenTasks {

    /**
     * How many there are among the issues the viewer, an admin, sees.
     *
     * @param companyId that company's issues only, or null for every company
     */
    long count(Viewer viewer, long accountId, Long companyId);

    /**
     * Clears the assignee of those tasks in the caller's transaction, each with an {@code ASSIGNEE}
     * change by {@code actorId}; nobody is told.
     *
     * @param companyId that company's issues only, or null for every company
     * @return how many tasks were cleared
     */
    int clear(long accountId, Long companyId, long actorId);
}
