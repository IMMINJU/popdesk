package io.popdesk.notification;

/**
 * What a queued webhook message is about; stored in {@code outbox.kind}. The send loop picks the text
 * by it (S0-AC-75). Staff comments send no webhook message.
 */
public enum WebhookKind {

    /** A customer filed an issue. */
    ISSUE_CREATED,

    /** A customer commented on an issue. */
    CUSTOMER_COMMENT
}
