package io.popdesk.helpdesk.web.console;

/** Why the audit log's hash chain does not hold (S1B-AC-14, S1B-AC-42). */
public enum AuditBreakReason {
    /** An entry's hash is not the hash of the entry before it and its own values. */
    ENTRY_MISMATCH,
    /** Every entry holds, but the last one is not the recorded end of the chain. */
    HEAD_MISMATCH
}
