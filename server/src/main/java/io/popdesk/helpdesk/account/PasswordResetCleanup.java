package io.popdesk.helpdesk.account;

import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deletes password reset links that were used or have expired (S1A-AC-15, OPS-2). Such a link already
 * sets no password ({@link PasswordResetService}); this removes its row. A job of its own, with its
 * own record.
 */
@Component
class PasswordResetCleanup implements CleanupJob {

    static final String NAME = "password-reset-cleanup";

    private final PasswordResetRepository resets;

    PasswordResetCleanup(PasswordResetRepository resets) {
        this.resets = resets;
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
        return resets.deleteEnded(now);
    }
}
