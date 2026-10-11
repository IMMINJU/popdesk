package io.popdesk.notification;

/**
 * Items in the console inbox of every {@code ACTIVE} admin that are about one thing that happened, not
 * about a condition that lasts: each call adds one item per admin, and nothing resolves them
 * ({@link OpsAlerts} is for conditions). No mail is sent.
 */
public interface AdminInbox {

    /**
     * A customer manager asked for an existing account to join this company (S2-AC-39): a
     * {@code MEMBERSHIP_REQUESTED} item with the company's code for each admin who is {@code ACTIVE} now.
     * Joins the caller's transaction, so the request and its items are kept or lost together.
     */
    void membershipRequested(String companyCode);
}
