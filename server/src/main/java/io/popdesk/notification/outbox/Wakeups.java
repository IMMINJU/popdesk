package io.popdesk.notification.outbox;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wakes a channel's send loop right after a transaction that queued a row commits (S0-AC-67), so a
 * new row does not wait for the loop's next round. A rolled back transaction wakes nothing.
 */
@Component
class Wakeups {

    private final Map<String, Semaphore> channels = Map.of(
            OutboxRepository.MAIL, new Semaphore(0),
            OutboxRepository.WEBHOOK, new Semaphore(0),
            OutboxRepository.AUDIT_FORWARD, new Semaphore(0));

    /** Wakes the channel's loop when the current transaction commits. */
    void afterCommit(String channel) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                channels.get(channel).release();
            }
        });
    }

    /** Wakes the channel's loop now, for a caller whose transaction has already committed. */
    void now(String channel) {
        channels.get(channel).release();
    }

    /** Waits until the channel is woken or {@code atMost} has passed. */
    void await(String channel, Duration atMost) throws InterruptedException {
        Semaphore wake = channels.get(channel);
        wake.tryAcquire(atMost.toMillis(), TimeUnit.MILLISECONDS);
        wake.drainPermits();
    }
}
