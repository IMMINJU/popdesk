package io.popdesk.notification;

/**
 * What a queued webhook message is about; stored in {@code outbox.kind}. The send loop picks the text
 * by it (S0-AC-75). Staff comments send no webhook message. An issue staff file on behalf of a customer
 * is {@code ISSUE_CREATED} too (S3-AC-75). The names are stored, so they never change.
 */
public enum WebhookKind {

    /** A customer filed an issue. */
    ISSUE_CREATED,

    /** A customer commented on an issue. */
    CUSTOMER_COMMENT,

    /** An issue became {@code RESOLVED} (S3-AC-73). */
    ISSUE_RESOLVED,

    /** Staff or the reporter closed an issue (S3-AC-73). */
    ISSUE_CLOSED,

    /** Staff or the reporter reopened an issue (S3-AC-73). */
    ISSUE_REOPENED,

    /** A person set, changed, or cleared the assignee of an issue (S3-AC-73). */
    ASSIGNEE_CHANGED,

    /** The first response goal of an issue passed its due time; one message per goal (S8B-AC-18). */
    SLA_RESPONSE_BREACHED,

    /** The resolution goal of an issue passed its due time; one message per goal (S8B-AC-18). */
    SLA_RESOLUTION_BREACHED
}
