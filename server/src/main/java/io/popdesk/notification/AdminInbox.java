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

    /**
     * A company export the account asked for became {@code DONE} (S10B-AC-04): one {@code COMPANY_EXPORT_READY} item
     * with the company's code for that account alone, and only while it is an {@code ACTIVE} admin: an account
     * suspended or anonymized meanwhile gets none (acceptance.md 5절 16). No email. Joins the caller's transaction,
     * so call it after the export's row is written, and the account is read as that transaction sees it.
     */
    void companyExportReady(String companyCode, long accountId);

    /**
     * A company export became {@code FAILED} (S10B-AC-05, OPS-1): a {@code COMPANY_EXPORT_FAILED} item with the
     * company's code for each admin who is {@code ACTIVE} now. No email. Joins the caller's transaction.
     */
    void companyExportFailed(String companyCode);

    /**
     * Automatic issue cleanup was turned on or shortened and moves issues 24 hours from now (S10B-AC-19, OPS-1): an
     * {@code ISSUE_RETENTION_ARMED} item for each admin who is {@code ACTIVE} now, about no issue, without an email.
     * Joins the caller's transaction.
     *
     * @param dueIssues how many issues the cleanup would move now, 0 or more
     * @param days the period in whole days, rounded down, 90 or more
     */
    void issueRetentionArmed(long dueIssues, long days);
}
