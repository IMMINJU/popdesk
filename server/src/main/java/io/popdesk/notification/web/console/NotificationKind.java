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
    ASSIGNED
}
