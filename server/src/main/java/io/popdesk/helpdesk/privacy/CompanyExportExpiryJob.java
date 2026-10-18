package io.popdesk.helpdesk.privacy;

import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deletes the ZIP of every company export that became {@code DONE} 24 hours ago, {@code company-export-expiry}, every
 * 10 minutes (S10B-AC-08, S10B-AC-23). The count recorded is the exports that became {@code EXPIRED}.
 */
@Component
class CompanyExportExpiryJob implements CleanupJob {

    static final String NAME = "company-export-expiry";

    private final CompanyExportService exports;

    CompanyExportExpiryJob(CompanyExportService exports) {
        this.exports = exports;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(10);
    }

    @Override
    public long run(Instant now) {
        return exports.expireDue(now);
    }
}
