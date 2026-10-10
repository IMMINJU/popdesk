package io.popdesk.notification.outbox;

import io.popdesk.InstanceLock;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * One send loop per channel (architecture.md "Outbox", D-39, S0-AC-67): a thread that takes the oldest
 * due row, sends it outside any transaction, and deletes the row or records the failure, until no row
 * is due. It runs again when a transaction that queued a row commits, and every second
 * without that ({@link Wakeups}). It pauses while this instance does not hold the single-instance lock (S0-AC-11).
 */
@Component
class SendLoops implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SendLoops.class);

    private static final Duration ROUND = Duration.ofSeconds(1);

    /** The error kind of an attempt that ended with an exception inside popdesk. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final OutboxDelivery delivery;
    private final OutboxRepository outbox;
    private final Wakeups wakeups;
    private final InstanceLock lock;
    private final List<Sender> senders;
    private final Map<String, Loop> loops = new ConcurrentHashMap<>();
    private volatile boolean running;

    SendLoops(OutboxDelivery delivery, OutboxRepository outbox, Wakeups wakeups, InstanceLock lock,
            List<Sender> senders) {
        this.delivery = delivery;
        this.outbox = outbox;
        this.wakeups = wakeups;
        this.lock = lock;
        this.senders = senders;
    }

    @Override
    public void start() {
        running = true;
        for (Sender sender : senders) {
            outbox.insertDeliveryStatus(sender.channel());
            Loop loop = new Loop(sender);
            loops.put(sender.channel(), loop);
            loop.thread.start();
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Loop loop : loops.values()) {
            loop.thread.interrupt();
        }
        loops.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private final class Loop implements Runnable {

        final Sender sender;
        final Thread thread;

        Loop(Sender sender) {
            this.sender = sender;
            this.thread = Thread.ofPlatform().daemon().name("popdesk-send-" + sender.channel().toLowerCase())
                    .unstarted(this);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    wakeups.await(sender.channel(), ROUND);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running || !lock.held()) {
                    continue;
                }
                try {
                    sendDue();
                } catch (RuntimeException e) {
                    // The rows stay due and are tried again on the next round. No message: it can hold an address.
                    log.warn("The {} send loop stopped this round ({}); it tries again in a second.",
                            sender.channel(), e.getClass().getSimpleName());
                }
            }
        }

        private void sendDue() {
            while (running && lock.held()) {
                Optional<OutboxRow> next = delivery.next(sender.channel());
                if (next.isEmpty()) {
                    return;
                }
                OutboxRow row = next.get();
                Attempt attempt = attempt(row);
                switch (attempt.outcome()) {
                    case SENT -> {
                        delivery.sent(row);
                        sender.forget(row.id());
                    }
                    case DROPPED -> {
                        delivery.dropped(row);
                        sender.forget(row.id());
                    }
                    case FAILED -> {
                        boolean givenUp = delivery.failed(row, attempt.errorKind());
                        log.warn("Sending outbox row {} ({}) failed: {}. {}", row.id(), sender.channel(),
                                attempt.errorKind(), givenUp ? "It is given up and left as failed."
                                        : "It is tried again later.");
                        if (givenUp) {
                            sender.forget(row.id());
                        }
                    }
                }
            }
        }

        /**
         * One attempt. A sender that throws, for example on a database error while it reads what to send,
         * counts as a failed attempt: without that the row would stay first in line with its attempts
         * unchanged and hold up the whole channel. Only the exception's class is logged; its message can
         * hold an address or a link.
         */
        private Attempt attempt(OutboxRow row) {
            try {
                return sender.attempt(row);
            } catch (RuntimeException e) {
                log.warn("Sending outbox row {} ({}) ended with {}.", row.id(), sender.channel(),
                        e.getClass().getSimpleName());
                return Attempt.failed(INTERNAL_ERROR);
            }
        }
    }
}
