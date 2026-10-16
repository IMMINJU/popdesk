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
    LINKED_ISSUE_CLOSED
}
