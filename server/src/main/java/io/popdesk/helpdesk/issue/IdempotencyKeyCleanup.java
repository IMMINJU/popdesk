package io.popdesk.helpdesk.issue;

import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deletes the keys of filings that are 24 hours old (S3-AC-46, OPS-2). Such a key already files a new
 * issue ({@link IssueService}); this removes its row. A job of its own, with its own record.
 */
@Component
class IdempotencyKeyCleanup implements CleanupJob {

    static final String NAME = "idempotency-key-cleanup";

    private final IdempotencyKeyRepository keys;

    IdempotencyKeyCleanup(IdempotencyKeyRepository keys) {
        this.keys = keys;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public long run(Instant now) {
        return keys.deleteWrittenUntil(now.minus(IdempotencyKey.KEPT));
    }
}
