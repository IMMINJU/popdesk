package io.popdesk.helpdesk.trash;

import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Purges the issues that have been in the trash for 30 days, {@code trash-purge}, every hour (TRASH-3,
 * S7A-AC-31). The count recorded is the issues purged.
 */
@Component
class TrashPurgeJob implements CleanupJob {

    static final String NAME = "trash-purge";

    private final TrashService trash;

    TrashPurgeJob(TrashService trash) {
        this.trash = trash;
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
        return trash.purgeDue(now);
    }
}
