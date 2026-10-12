package io.popdesk.notification;

import org.jspecify.annotations.Nullable;

/**
 * The audit entries of delivery operations (OPS-3, AUDIT-1): helpdesk owns the audit log and implements
 * this. An entry names who did it and nothing of what was sent: no address, no title, no URL, and not
 * what an SMTP server answered (S4B-AC-20, S4B-AC-27).
 */
public interface DeliveryAudit {

    /**
     * Records {@code DELIVERY_RESENT} in the caller's transaction; fails when there is none.
     *
     * @param actorAccountId the admin who sent the row again
     * @param channel the row's channel: {@code MAIL}, {@code WEBHOOK} or {@code AUDIT_FORWARD}
     * @param kind the row's kind, as the outbox keeps it
     */
    void resent(long actorAccountId, String channel, String kind);

    /**
     * Records {@code TEST_MAIL_SENT} in a transaction of its own. Call it outside any transaction, after
     * the email was tried.
     *
     * @param actorAccountId the admin who sent the test email
     * @param ok whether the SMTP server took it
     * @param errorKind the kind of the error when it did not, else null
     */
    void testMailSent(long actorAccountId, boolean ok, @Nullable String errorKind);
}
