package io.popdesk.helpdesk;

/**
 * The open issues a person is the assignee of (ACCT-7), as the account and company services need
 * them: how many there are, and clearing them. The issue package implements it, so that accounts and
 * companies do not depend on issues (ADR D-57).
 */
public interface OpenAssignments {

    /**
     * How many open issues the account is the assignee of, among those the viewer, an admin, sees.
     *
     * @param companyId that company only, or null for every company
     */
    long count(Viewer viewer, long accountId, Long companyId);

    /**
     * Clears the assignee of those issues in the caller's transaction; there must be one.
     *
     * @param companyId that company only, or null for every company
     * @param actorId the admin, for the history entry of each cleared issue
     * @return how many issues were cleared
     */
    int clear(long accountId, Long companyId, long actorId);
}
