package io.popdesk.notification.outbox;

import io.popdesk.notification.CleanupJob;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Deletes {@code failed} outbox rows 7 days after they failed (D-39, S0-AC-68). */
@Component
class OutboxCleanup implements CleanupJob {

    static final String NAME = "outbox-failed-cleanup";

    /** How long a failed row stays for people to look at. */
    static final Duration KEEP = Duration.ofDays(7);

    private final OutboxRepository outbox;

    OutboxCleanup(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Often, so a row goes soon after its 7 days; the run is one indexed delete. */
    @Override
    public Duration interval() {
        return Duration.ofMinutes(1);
    }

    @Override
    public long run(Instant now) {
        return outbox.deleteFailedBefore(now.minus(KEEP));
    }
}
