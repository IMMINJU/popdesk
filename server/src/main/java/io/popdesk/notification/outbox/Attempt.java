package io.popdesk.notification.outbox;

import java.util.UUID;

/**
 * What came of handling one outbox row. A sent email has the {@code Message-ID} popdesk gave it, without the angle
 * brackets; {@code recordIssueId} is the issue it is to be recorded as about, so that a reply finds the issue
 * (S12-AC-05), else null.
 */
record Attempt(Outcome outcome, String errorKind, String messageId, UUID recordIssueId) {

    enum Outcome {
        /** The receiving side took it. */
        SENT,
        /** Not to be sent at all (S0-AC-69, S0-AC-73): the row goes without an attempt. */
        DROPPED,
        /** The receiving side refused or could not be reached; tried again later (S0-AC-68). */
        FAILED,
        /** Not sent and not to be tried again: the row is left {@code failed} at once (S4B-AC-10). */
        STOPPED
    }

    static Attempt sent() {
        return new Attempt(Outcome.SENT, null, null, null);
    }

    /** @param messageId the {@code Message-ID} of the sent email, without the angle brackets */
    static Attempt sent(String messageId) {
        return new Attempt(Outcome.SENT, null, messageId, null);
    }

    static Attempt dropped() {
        return new Attempt(Outcome.DROPPED, null, null, null);
    }

    /** @param errorKind a short name such as {@code CONNECTION_FAILED}; never an error message or an address */
    static Attempt failed(String errorKind) {
        return new Attempt(Outcome.FAILED, errorKind, null, null);
    }

    /** @param errorKind a short name such as {@code RATE_LIMITED} */
    static Attempt stopped(String errorKind) {
        return new Attempt(Outcome.STOPPED, errorKind, null, null);
    }

    /** This attempt, recorded as about the issue when it is a sent email with a {@code Message-ID}. */
    Attempt recordedAbout(UUID issueId) {
        return outcome == Outcome.SENT && messageId != null ? new Attempt(outcome, null, messageId, issueId) : this;
    }
}
