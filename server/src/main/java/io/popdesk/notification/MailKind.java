package io.popdesk.notification;

/**
 * What a queued email is about; stored in {@code outbox.kind}. The send loop picks the template by it.
 * Add a value here for each new kind of email.
 */
public enum MailKind {

    /** An invitation to the recipient's app. The send loop makes the link when it sends the email. */
    INVITATION,

    /**
     * A public comment by staff on an issue, to the issue's reporter (S0-AC-70). Internal comments queue
     * nothing (S0-AC-53).
     */
    STAFF_COMMENT
}
