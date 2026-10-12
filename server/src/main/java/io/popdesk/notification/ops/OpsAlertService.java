package io.popdesk.notification.ops;

import io.popdesk.notification.OpsAlerts;
import io.popdesk.notification.Recipients;
import io.popdesk.notification.outbox.Channels;
import io.popdesk.notification.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OpsAlerts} on the tables {@code ops_alert} and {@code notification} (S0-AC-80). A raised alert
 * is one {@code ops_alert} row per kind; it goes when the alert is resolved.
 */
@Service
class OpsAlertService implements OpsAlerts {

    /** How often admins hear of an alert that stays raised. */
    static final Duration REPEAT = Duration.ofHours(24);

    /** How long a channel only fails before the admins hear of it (S4B-AC-29). */
    static final Duration STALLED_AFTER = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(OpsAlertService.class);

    private final OpsRepository ops;
    private final OutboxRepository outbox;
    private final Channels channels;
    private final Recipients recipients;
    private final Clock clock;

    OpsAlertService(OpsRepository ops, OutboxRepository outbox, Channels channels, Recipients recipients,
            Clock clock) {
        this.ops = ops;
        this.outbox = outbox;
        this.channels = channels;
        this.recipients = recipients;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void raise(Kind kind) {
        Instant now = now();
        if (ops.insertAlert(kind.name(), now)) {
            log.warn("Operations alert {} raised; the admins have an item in their inbox.", kind);
            notifyAdmins(kind, now);
        }
    }

    @Override
    @Transactional
    public void resolve(Kind kind) {
        ops.deleteAlert(kind.name());
    }

    /**
     * Once a scheduler round: resolves {@link Kind#DELIVERY_FAILED} when no failed outbox row is left,
     * raises or resolves the alert of each channel by whether it only fails ({@link #stalled}), and
     * tells the admins again of each alert raised for 24 hours since they last heard of it.
     */
    @Transactional
    public void review() {
        if (!outbox.anyFailed()) {
            ops.deleteAlert(Kind.DELIVERY_FAILED.name());
        }
        Instant now = now();
        for (String channel : OutboxRepository.CHANNELS) {
            if (stalled(channel, now)) {
                raise(Kind.stalled(channel));
            } else {
                ops.deleteAlert(Kind.stalled(channel).name());
            }
        }
        for (String kind : ops.lockAlertsNotifiedBefore(now.minus(REPEAT))) {
            notifyAdmins(Kind.valueOf(kind), now);
            ops.updateNotified(kind, now);
        }
    }

    /**
     * Whether the channel has only failed for over an hour (S4B-AC-29): a row still in its queue first
     * failed more than an hour ago, and the channel sent nothing in that hour. A row that was given up
     * is {@code failed}, not queued, and is {@link Kind#DELIVERY_FAILED}'s business. A channel that is
     * off is never stalled, whatever its queue holds.
     */
    private boolean stalled(String channel, Instant now) {
        Instant before = now.minus(STALLED_AFTER);
        return channels.enabled(channel) && outbox.anyFailingSince(channel, before)
                && outbox.lastSuccess(channel).map(at -> at.isBefore(before)).orElse(true);
    }

    private void notifyAdmins(Kind kind, Instant now) {
        for (long admin : recipients.activeAdmins()) {
            ops.insertInbox(admin, kind.name(), now);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
