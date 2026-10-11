package io.popdesk.notification.web.console;

/** An outgoing channel of the outbox. */
public enum DeliveryChannel {
    MAIL,
    WEBHOOK,
    AUDIT_FORWARD
}
