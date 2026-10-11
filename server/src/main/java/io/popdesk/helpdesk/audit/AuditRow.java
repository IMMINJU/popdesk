package io.popdesk.helpdesk.audit;

import java.time.Instant;

/**
 * One stored audit entry, as the database gives it: {@code actor}, {@code target} and {@code changes}
 * are the stored JSON as text ({@code target} and {@code changes} may be null), and the hashes are the
 * stored ones, never computed again here.
 */
public record AuditRow(long id, long seq, String prevHash, String hash, String action, String actor,
        String target, String changes, Instant occurredAt) {

    /** The values the hash covers. */
    AuditChain.Values values() {
        return new AuditChain.Values(action, actor, target, changes, occurredAt);
    }
}
