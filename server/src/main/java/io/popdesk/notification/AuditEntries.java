package io.popdesk.notification;

import java.util.Optional;

/**
 * The audit entries notification copies to the outside address, read when it sends (S1B-AC-29):
 * helpdesk owns the audit log and implements this. Notification never stores an entry.
 */
public interface AuditEntries {

    /**
     * The entry as one JSON object, with the field names of an export line: {@code id}, {@code seq},
     * {@code prevHash}, {@code hash}, {@code action}, {@code actor}, {@code target}, {@code changes},
     * {@code occurredAt}. Empty when the entry is gone (the retention period removed it).
     */
    Optional<String> body(long auditId);
}
