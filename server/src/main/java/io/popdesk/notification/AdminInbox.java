package io.popdesk.notification;

/**
 * Items in the console inbox of every {@code ACTIVE} admin that are about one thing that happened, not
 * about a condition that lasts: each call adds one item per admin, and nothing resolves them
 * ({@link OpsAlerts} is for conditions).
 */
public interface AdminInbox {

    /**
     * A customer manager asked for an existing account to join this company (S2-AC-39): a
     * {@code MEMBERSHIP_REQUESTED} item with the company's code for each admin who is {@code ACTIVE} now,
     * and one email to each of them (S4-AC-51). The account the request is about gets no email, also
     * when it is an admin; its item is added like the others. Joins the caller's transaction, so the
     * request, its items, and the queued emails are kept or lost together.
     *
     * @param aboutAccountId the account the manager asked for
     */
    void membershipRequested(String companyCode, long aboutAccountId);

    /**
     * A recalculation of the SLA due times ended and changed something (S8B-AC-14): a {@code SLA_RECALCULATED} item
     * with the three counts for each admin who is {@code ACTIVE} now, about no issue, without an email. Joins the
     * caller's transaction, so the closed summary and its items are kept or lost together.
     *
     * @param becameBreached issues that became overdue, 0 or more
     * @param becameWarning issues that came into warning from OK or from no goal, 0 or more
     * @param released issues that moved back from overdue or warning, 0 or more
     */
    void slaRecalculated(int becameBreached, int becameWarning, int released);
}
