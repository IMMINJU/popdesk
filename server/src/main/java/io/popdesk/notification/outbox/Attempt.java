package io.popdesk.notification.outbox;

/** What came of handling one outbox row. */
record Attempt(Outcome outcome, String errorKind) {

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
        return new Attempt(Outcome.SENT, null);
    }

    static Attempt dropped() {
        return new Attempt(Outcome.DROPPED, null);
    }

    /** @param errorKind a short name such as {@code CONNECTION_FAILED}; never an error message or an address */
    static Attempt failed(String errorKind) {
        return new Attempt(Outcome.FAILED, errorKind);
    }

    /** @param errorKind a short name such as {@code RATE_LIMITED} */
    static Attempt stopped(String errorKind) {
        return new Attempt(Outcome.STOPPED, errorKind);
    }
}
