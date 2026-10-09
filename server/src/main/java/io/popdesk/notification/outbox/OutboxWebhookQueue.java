package io.popdesk.notification.outbox;

import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.WebhookQueue;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link WebhookQueue} on the {@code outbox} table. Sending is the send loop's work. */
@Service
class OutboxWebhookQueue implements WebhookQueue {

    private final OutboxRepository repository;
    private final Clock clock;
    private final boolean enabled;

    OutboxWebhookQueue(OutboxRepository repository, Clock clock, Environment environment) {
        this.repository = repository;
        this.clock = clock;
        String url = environment.getProperty("NOTIFY_WEBHOOK_URL");
        this.enabled = url != null && !url.isBlank();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(WebhookKind kind, UUID issueId, @Nullable Long commentId) {
        if (!enabled) {
            return;
        }
        repository.insert(OutboxRepository.WEBHOOK, kind.name(), null, issueId, commentId,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
