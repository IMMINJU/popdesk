package io.popdesk.notification;

/**
 * The outside copy of the audit log ({@code AUDIT_FORWARD_URL}, AUDIT-9) as the app's audit log sees
 * it. The queue rows themselves are written with {@link AuditCopyRows}, on the connection that
 * appends the entries.
 */
public interface AuditCopies {

    /** Whether the copy is on ({@link AuditCopyRows#enabled}). Off, no row is queued at all (S1B-AC-31). */
    boolean enabled();

    /** Wakes the copy's send loop. Call it after the transaction that queued rows has committed. */
    void wake();
}
