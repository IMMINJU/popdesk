package io.popdesk.notification;

/**
 * What a queued email is about; stored in {@code outbox.kind}. The send loop picks the template by it.
 * Add a value here for each new kind of email.
 */
public enum MailKind {

    /** An invitation to the recipient's app. The send loop makes the link when it sends the email. */
    INVITATION,

    /**
     * A password reset link for the recipient's app (S1A-AC-06). The send loop makes the link when it
     * sends the email.
     */
    PASSWORD_RESET,

    /**
     * A public comment by staff on an issue, to the issue's reporter (S0-AC-70). Internal comments queue
     * nothing (S0-AC-53).
     */
    STAFF_COMMENT,

    /** Staff filed an issue on behalf of a customer, to that customer (S3-AC-37). */
    ISSUE_FILED_ON_BEHALF,

    /** Staff resolved an issue, to its reporter (S4-AC-48). */
    ISSUE_RESOLVED,

    /** Staff closed an issue, to its reporter (S4-AC-48). */
    ISSUE_CLOSED,

    /** Staff reopened an issue, to its reporter (S4-AC-48). */
    ISSUE_REOPENED,

    /** A comment mentions the recipient (S4-AC-50). */
    MENTIONED,

    /** The recipient became the assignee of an issue (S4-AC-49). */
    ASSIGNED,

    /** A customer manager asked for an existing account to join a company, to an admin (S4-AC-51). */
    MEMBERSHIP_REQUESTED,

    /** The recipient became the assignee of a task (S6B-AC-33, S6B-AC-34). */
    TASK_ASSIGNED
}
