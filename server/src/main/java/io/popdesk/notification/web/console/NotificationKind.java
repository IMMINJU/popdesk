package io.popdesk.notification.web.console;

/** Kinds of inbox items. The first slice has only operational alerts for admins (OPS-1). */
public enum NotificationKind {
    /** A row of the outbox became {@code failed}. */
    DELIVERY_FAILED,
    /** Attachments passed 80% of the team quota. */
    ATTACHMENT_QUOTA_WARNING
}
