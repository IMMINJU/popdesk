package io.popdesk.notification.outbox;

import io.popdesk.notification.AuditCopies;
import io.popdesk.notification.AuditCopyRows;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** {@link AuditCopies} for the send loop of the {@code AUDIT_FORWARD} channel. */
@Component
class OutboxAuditCopies implements AuditCopies {

    private final Wakeups wakeups;
    private final boolean enabled;

    OutboxAuditCopies(Wakeups wakeups, Environment environment) {
        this.wakeups = wakeups;
        this.enabled = AuditCopyRows.enabled(environment::getProperty);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void wake() {
        wakeups.now(OutboxRepository.AUDIT_FORWARD);
    }
}
