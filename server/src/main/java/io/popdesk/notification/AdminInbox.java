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
}
