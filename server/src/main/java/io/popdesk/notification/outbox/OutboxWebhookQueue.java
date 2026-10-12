package io.popdesk.notification.outbox;

import io.popdesk.notification.Sandbox;
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

/**
 * {@link WebhookQueue} on the {@code outbox} table. Sending is the send loop's work. A sandbox queues
 * nothing although the address is set (S4B-AC-04).
 */
@Service
class OutboxWebhookQueue implements WebhookQueue {

    private final OutboxRepository repository;
    private final Wakeups wakeups;
    private final Clock clock;
    private final boolean enabled;

    OutboxWebhookQueue(OutboxRepository repository, Wakeups wakeups, Clock clock, Environment environment,
            Sandbox sandbox) {
        this.repository = repository;
        this.wakeups = wakeups;
        this.clock = clock;
        this.enabled = Channels.webhookOn(environment, sandbox);
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
        wakeups.afterCommit(OutboxRepository.WEBHOOK);
    }
}
