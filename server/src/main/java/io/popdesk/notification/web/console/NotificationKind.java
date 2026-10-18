package io.popdesk.notification.web.console;

/**
 * Kinds of inbox items. The names are stored, so they never change. Who did it is the item's
 * {@code actor}, not a kind of its own: {@code ISSUE_CLOSED} is the kind whether staff or the reporter
 * closed the issue.
 */
public enum NotificationKind {
    /** A row of the outbox became {@code failed}. */
    DELIVERY_FAILED,
    /** Attachments passed 80% of the team quota. */
    ATTACHMENT_QUOTA_WARNING,
    /** Sending email has only failed for over an hour (S4B-AC-29). */
    MAIL_STALLED,
    /** Sending the webhook has only failed for over an hour (S4B-AC-29). */
    WEBHOOK_STALLED,
    /** Sending the outside copy of the audit log has only failed for over an hour (S4B-AC-29). */
    AUDIT_FORWARD_STALLED,
    /** A cleanup job has not succeeded for its interval plus 24 hours (S7B-AC-10). */
    CLEANUP_STALLED,
    /** A customer manager invited an email that already has an account (S2-AC-39). */
    MEMBERSHIP_REQUESTED,
    /** Staff added a public comment, to the reporter (S4-AC-27). Never in a staff inbox. */
    STAFF_COMMENT,
    /** The reporter added a comment, to the assignee (S4-AC-28). */
    CUSTOMER_COMMENT,
    /** Staff resolved the issue, to the reporter (S4-AC-29). Never in a staff inbox. */
    ISSUE_RESOLVED,
    /** The reporter closed the issue, to the assignee (S4-AC-30); by staff, to the reporter (S4-AC-29). */
    ISSUE_CLOSED,
    /** The reporter reopened the issue, to the assignee (S4-AC-30); by staff, to the reporter (S4-AC-29). */
    ISSUE_REOPENED,
    /** Staff filed the issue on behalf of the reporter (S4-AC-32). Never in a staff inbox. */
    ISSUE_FILED_ON_BEHALF,
    /** A comment mentions the caller (S4-AC-33). */
    MENTIONED,
    /** The caller became the assignee of the issue (S4-AC-31). */
    ASSIGNED,
    /** The caller became the assignee of a task of the issue; the item's {@code task} names it (S6B-AC-33). */
    TASK_ASSIGNED,
    /**
     * An issue linked to the caller's issue became {@code RESOLVED}; the item's {@code issue} is the
     * caller's issue and {@code linkedIssue} the resolved one (S6C-AC-31). No email.
     */
    LINKED_ISSUE_RESOLVED,
    /** An issue linked to the caller's issue became {@code CLOSED}, like {@link #LINKED_ISSUE_RESOLVED}. */
    LINKED_ISSUE_CLOSED,
    /**
     * The first response goal of the issue passed 80% as time went by (S8B-AC-01). To the assignee, else the
     * agents of the company, else every admin (S8B-AC-03). No email, no webhook; {@code actor} is null.
     */
    SLA_RESPONSE_WARNING,
    /**
     * The first response goal of the issue passed its due time (S8B-AC-02). To the people of
     * {@link #SLA_RESPONSE_WARNING} and every admin, with an email; {@code actor} is null.
     */
    SLA_RESPONSE_BREACHED,
    /** The resolution goal of the issue passed 80%, like {@link #SLA_RESPONSE_WARNING}. */
    SLA_RESOLUTION_WARNING,
    /** The resolution goal of the issue passed its due time, like {@link #SLA_RESPONSE_BREACHED}. */
    SLA_RESOLUTION_BREACHED,
    /**
     * Working the SLA due times out again finished and changed something; to every admin, about no issue.
     * The item's {@code slaSummary} has the counts (S8B-AC-14, S8B-AC-15). No email, no webhook.
     */
    SLA_RECALCULATED,
    /**
     * A company export the caller asked for became {@code DONE}; to that admin only, and not when the
     * account is no longer {@code ACTIVE} or is anonymized (S10B-AC-04). {@code companyCode} names the
     * company; about no issue, {@code actor} is null. No email.
     */
    COMPANY_EXPORT_READY,
    /** A company export became {@code FAILED}; to every admin, like {@link #COMPANY_EXPORT_READY} (S10B-AC-05). */
    COMPANY_EXPORT_FAILED,
    /**
     * Automatic issue cleanup was turned on or shortened and starts moving issues in 24 hours; to every
     * admin, about no issue. {@code retentionCount} and {@code retentionDays} have the numbers (S10B-AC-19).
     */
    ISSUE_RETENTION_ARMED
}
