package io.popdesk.notification.ops;

import io.popdesk.notification.OpsAlerts;
import io.popdesk.notification.Recipients;
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

    private static final Logger log = LoggerFactory.getLogger(OpsAlertService.class);

    private final OpsRepository ops;
    private final OutboxRepository outbox;
    private final Recipients recipients;
    private final Clock clock;

    OpsAlertService(OpsRepository ops, OutboxRepository outbox, Recipients recipients, Clock clock) {
        this.ops = ops;
        this.outbox = outbox;
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
     * Once a scheduler round: resolves {@link Kind#DELIVERY_FAILED} when no failed outbox row is left, and
     * tells the admins again of each alert raised for 24 hours since they last heard of it.
     */
    @Transactional
    public void review() {
        if (!outbox.anyFailed()) {
            ops.deleteAlert(Kind.DELIVERY_FAILED.name());
        }
        Instant now = now();
        for (String kind : ops.lockAlertsNotifiedBefore(now.minus(REPEAT))) {
            notifyAdmins(Kind.valueOf(kind), now);
            ops.updateNotified(kind, now);
        }
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
