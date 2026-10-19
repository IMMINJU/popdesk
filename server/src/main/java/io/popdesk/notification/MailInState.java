package io.popdesk.notification;

import java.time.Instant;
import java.util.Optional;

/**
 * Receiving email (MAILIN) as notification needs to know it: whether replies go to popdesk, for the {@code Reply-To}
 * and {@code Message-ID} of the email it sends (S12-AC-04, S12-AC-05), and what the system status says (OPS-3,
 * S12-AC-36). helpdesk reads the mailbox and implements this, so notification asks without reading helpdesk.
 */
public interface MailInState {

    /** Why receiving email is off. */
    enum OffReason {
        /** {@code IMAP_HOST}, {@code IMAP_USER}, {@code IMAP_PASSWORD}, {@code MAIL_INBOUND_ADDRESS} are not set. */
        NOT_SET,
        /** They are, and the sandbox turned receiving off (S12-AC-03). */
        SANDBOX
    }

    /** The kinds of round failure (S12-AC-10). */
    enum FailureKind {
        CONNECT,
        AUTH,
        FOLDER,
        UNSUPPORTED
    }

    /**
     * One reading. {@code offReason} is null when on; {@code lastSuccessAt}, {@code lastFailureKind}, and
     * {@code lastFailureAt} are null when there was none since the app started. {@code retrying} and
     * {@code quarantined90d} are read from the received mail records.
     */
    record Snapshot(boolean enabled, OffReason offReason, Instant lastSuccessAt, int roundFailures,
            FailureKind lastFailureKind, Instant lastFailureAt, int retrying, int quarantined90d) {}

    Snapshot snapshot();

    /**
     * {@code MAIL_INBOUND_ADDRESS} while receiving email is on: a customer's email about an issue gets it as
     * {@code Reply-To}. Empty when off, in a sandbox too.
     */
    Optional<String> replyTo();

    /**
     * The domain of {@code MAIL_INBOUND_ADDRESS} in lower case, also when the sandbox turned receiving off: the
     * {@code Message-ID} of every email is at it (S12-AC-04). Empty without the address.
     */
    Optional<String> messageIdDomain();
}
