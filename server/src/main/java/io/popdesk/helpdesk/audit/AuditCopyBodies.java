package io.popdesk.helpdesk.audit;

import io.popdesk.notification.AuditEntries;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What the notification module reads when it sends an entry's outside copy (S1B-AC-29): the stored
 * entry as the JSON Lines export writes it, stored hashes included. Nothing is computed again.
 */
@Component
class AuditCopyBodies implements AuditEntries {

    private final AuditRepository repository;

    AuditCopyBodies(AuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> body(long auditId) {
        return repository.find(auditId).map(AuditExportLines::jsonLine);
    }
}
