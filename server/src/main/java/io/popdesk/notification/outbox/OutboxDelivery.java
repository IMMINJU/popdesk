package io.popdesk.notification.outbox;

import io.popdesk.notification.OpsAlerts;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions around one send (D-39, S0-AC-67, S0-AC-68): take the next due row, then, after
 * the send has happened outside any transaction, delete the row or record the failure.
 */
@Service
class OutboxDelivery {

    /** The first wait after a failure; it doubles with each failure. */
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);

    /** The longest wait between two attempts. */
    static final Duration MAX_WAIT = Duration.ofMinutes(10);

    /** How long after the first failure a row is given up. */
    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

    private final OutboxRepository outbox;
    private final OpsAlerts alerts;
    private final OutboxMetrics metrics;
    private final MailSentRepository mailSent;
    private final Clock clock;

    OutboxDelivery(OutboxRepository outbox, OpsAlerts alerts, OutboxMetrics metrics, MailSentRepository mailSent,
            Clock clock) {
        this.outbox = outbox;
        this.alerts = alerts;
        this.metrics = metrics;
        this.mailSent = mailSent;
        this.clock = clock;
    }

    /** The oldest due row of the channel. The lock ends with this transaction, before the send. */
    @Transactional
    public Optional<OutboxRow> next(String channel) {
        return outbox.lockNextDue(channel, now());
    }

    /**
     * The row was sent: it goes, the channel's last success is now, and an alert that the channel only
     * fails is resolved (S4B-AC-29).
     */
    @Transactional
    public void sent(OutboxRow row) {
        outbox.delete(row.id());
        outbox.updateLastSuccess(row.channel(), now());
        alerts.resolve(OpsAlerts.Kind.stalled(row.channel()));
        metrics.sent(row.channel());
    }

    /**
     * Records the {@code Message-ID} of a sent email and its issue (S12-AC-05), in a transaction of its own after the
     * one of {@link #sent}: when this fails, the row is gone all the same and the email is not sent twice.
     */
    @Transactional
    public void recordSent(String messageId, UUID issueId) {
        mailSent.insert(messageId, issueId, now());
    }

    /** The row is not to be sent. */
    @Transactional
    public void dropped(OutboxRow row) {
        outbox.delete(row.id());
    }

    /**
     * An attempt failed. The next one is 1 second after the first failure, doubling up to 10 minutes; an
     * attempt that fails 24 hours or more after the first failure leaves the row {@code failed} and
     * raises {@link OpsAlerts.Kind#DELIVERY_FAILED}.
     *
     * @return whether the row was given up
     */
    @Transactional
    public boolean failed(OutboxRow row, String errorKind) {
        Instant now = now();
        int attempts = row.attempts() + 1;
        Instant firstFailedAt = row.firstFailedAt() == null ? now : row.firstFailedAt();
        if (!now.isBefore(firstFailedAt.plus(GIVE_UP_AFTER))) {
            outbox.updateFailed(row.id(), attempts, firstFailedAt, now, errorKind);
            alerts.raise(OpsAlerts.Kind.DELIVERY_FAILED);
            return true;
        }
        outbox.updateRetry(row.id(), attempts, firstFailedAt, now.plus(wait(attempts)), errorKind);
        return false;
    }

    /**
     * The row was stopped before any attempt (the mail limit, S4B-AC-10): it is {@code failed} at once,
     * as a row that was given up, and raises {@link OpsAlerts.Kind#DELIVERY_FAILED} (S4B-AC-12). Sending
     * it again later is a person's decision.
     */
    @Transactional
    public void stopped(OutboxRow row, String errorKind) {
        Instant now = now();
        outbox.updateFailed(row.id(), row.attempts(), row.firstFailedAt() == null ? now : row.firstFailedAt(), now,
                errorKind);
        alerts.raise(OpsAlerts.Kind.DELIVERY_FAILED);
    }

    /** The wait after the {@code attempts}-th failure: 1, 2, 4 ... seconds, at most 10 minutes. */
    static Duration wait(int attempts) {
        int doublings = Math.min(attempts - 1, 30);
        Duration wait = FIRST_WAIT.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
