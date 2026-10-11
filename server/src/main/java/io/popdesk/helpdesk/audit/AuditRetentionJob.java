package io.popdesk.helpdesk.audit;

import io.popdesk.notification.CleanupJob;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Deletes audit entries older than {@code POPDESK_AUDIT_RETENTION}, once a day (AUDIT-4, S1B-AC-19).
 * Only from the front of the chain ({@link AuditService#removeOlderThan}); what it removes and where
 * the chain then starts change together.
 */
@Component
class AuditRetentionJob implements CleanupJob {

    static final String NAME = "audit-retention";

    private final AuditService audit;
    private final AuditRetention retention;

    AuditRetentionJob(AuditService audit, AuditRetention retention) {
        this.audit = audit;
        this.retention = retention;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofDays(1);
    }

    /** With a period that is not valid nothing is deleted; the app takes no requests then either. */
    @Override
    public long run(Instant now) {
        Optional<Duration> period = retention.period();
        if (period.isEmpty()) {
            return 0;
        }
        Instant cutoff;
        try {
            cutoff = now.minus(period.get());
        } catch (DateTimeException | ArithmeticException e) {
            // A period longer than time itself: nothing is that old.
            return 0;
        }
        return audit.removeOlderThan(cutoff);
    }
}
