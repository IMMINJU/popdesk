package io.popdesk.notification;

/**
 * Operations alerts (S0-AC-80, OPS-1): an item in the inbox of every {@code ACTIVE} admin when
 * something needs a person.
 *
 * <p>An alert of one kind is raised until it is resolved. Raising it again meanwhile adds no item;
 * while it stays raised, the admins get one more item every 24 hours of the app clock. Once resolved,
 * the next raise gives items again at once.
 *
 * <p>Notification raises and resolves {@link Kind#DELIVERY_FAILED} and the four {@code _STALLED} kinds
 * itself. The module that knows the condition of another kind raises it and resolves it.
 */
public interface OpsAlerts {

    /** Kinds of alerts; the inbox item's {@code kind}. */
    enum Kind {
        /** An outbox row became {@code failed}; resolved when no failed row is left. */
        DELIVERY_FAILED,
        /** Attachments are over 80% of {@code POPDESK_ATTACHMENT_QUOTA}; resolved when they are back under. */
        ATTACHMENT_QUOTA_WARNING,
        /**
         * Sending email has only failed for over an hour (S4B-AC-29): a row has waited that long since
         * its first failure and nothing went out meanwhile. Resolved when an email goes out.
         */
        MAIL_STALLED,
        /** As {@link #MAIL_STALLED}, for the webhook. */
        WEBHOOK_STALLED,
        /** As {@link #MAIL_STALLED}, for the outside copy of the audit log. */
        AUDIT_FORWARD_STALLED,
        /**
         * A cleanup job has not succeeded for its interval plus 24 hours since its last success (S7B-AC-10);
         * one alert for any number of such jobs. Resolved when no job is that late (S7B-AC-11).
         */
        CLEANUP_STALLED;

        /** The kind for a channel that only fails; {@code channel} as the outbox names it. */
        public static Kind stalled(String channel) {
            return valueOf(channel + "_STALLED");
        }
    }

    /** Raises the alert; the admins get an item now unless it is raised already. Joins the caller's transaction. */
    void raise(Kind kind);

    /** Resolves the alert, if it is raised. Joins the caller's transaction. */
    void resolve(Kind kind);
}
