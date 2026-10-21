package io.popdesk.helpdesk.mailin;

import io.popdesk.InstanceLock;
import io.popdesk.helpdesk.mailin.ImapMailbox.Connection;
import io.popdesk.helpdesk.mailin.ImapMailbox.Mail;
import io.popdesk.helpdesk.mailin.ImapMailbox.Read;
import io.popdesk.helpdesk.mailin.ImapMailbox.RoundFailure;
import io.popdesk.helpdesk.mailin.MailInProcessor.Destination;
import io.popdesk.helpdesk.mailin.MailInProcessor.Incoming;
import io.popdesk.notification.MailInState.FailureKind;
import io.popdesk.notification.OpsAlerts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The rounds of receiving email (MAILIN-8, D-67, S12-AC-06): a thread that wakes every second of real time and, while
 * receiving email is on and this instance holds the single-instance lock (D-23), starts a round right after the app
 * starts and then each minute of the app's clock. A round reads up to 100 mails of the incoming folder, hands each to
 * {@link MailInProcessor}, and moves it after its outcome is committed; once a day it also deletes the old mail of the
 * processed and quarantine folders (S12-AC-43) and resolves {@code MAIL_IN_QUARANTINED} after a day without quarantine
 * (S12-AC-37). The round's success is written last, after all of that (design.md 0절 15 ①).
 *
 * <p>A failed round counts its kind and leaves every mail where it is (S12-AC-10). After an hour of failures since the
 * first one, {@code MAIL_IN_STALLED} is raised; only a good round resolves it (design.md 0절 4).
 */
@Component
class MailInLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MailInLoop.class);

    /** App time between the starts of two rounds. */
    static final Duration ROUND = Duration.ofMinutes(1);

    /** The most mails one round reads (S12-AC-06). */
    static final int MAILS_PER_ROUND = 100;

    /** How long rounds fail before {@code MAIL_IN_STALLED} (S12-AC-37). */
    static final Duration STALLED_AFTER = Duration.ofHours(1);

    /** How often the mailbox is cleaned and the quarantine alert looked at. */
    static final Duration DAILY = Duration.ofDays(1);

    /** How long handled and quarantined mail stays in the mailbox (S12-AC-43, PII-6). */
    static final Duration KEPT = Duration.ofDays(90);

    /** How long without quarantine resolves {@code MAIL_IN_QUARANTINED} (S12-AC-37). */
    static final Duration QUARANTINE_QUIET = Duration.ofHours(24);

    private final MailInSetup setup;
    private final ImapMailbox mailbox;
    private final MailInProcessor processor;
    private final MailInStatus status;
    private final MailReceivedRepository records;
    private final MailInMetrics metrics;
    private final OpsAlerts alerts;
    private final InstanceLock lock;
    private final Clock clock;

    private Instant lastRoundAt;
    private Instant lastDailyAt;
    private Instant failingSince;

    /**
     * The rounds each mail with neither bytes nor header was skipped in, by UIDVALIDITY and UID (B7-AC-13). Only the
     * rounds' thread uses it; it starts empty when the app starts.
     */
    private final Map<String, Integer> skippedRounds = new HashMap<>();
    private volatile ScheduledExecutorService scheduler;

    MailInLoop(MailInSetup setup, ImapMailbox mailbox, MailInProcessor processor, MailInStatus status,
            MailReceivedRepository records, MailInMetrics metrics, OpsAlerts alerts, InstanceLock lock, Clock clock) {
        this.setup = setup;
        this.mailbox = mailbox;
        this.processor = processor;
        this.status = status;
        this.records = records;
        this.metrics = metrics;
        this.alerts = alerts;
        this.lock = lock;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!setup.settings().enabled()) {
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("popdesk-mail-in").factory());
        s.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
        scheduler = s;
    }

    @Override
    public void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    /** One wake of the thread; a failure that nothing else caught is logged by its kind and the thread goes on. */
    private void tick() {
        try {
            if (!lock.held()) {
                return;
            }
            Instant now = clock.instant();
            if (lastRoundAt != null && now.isBefore(lastRoundAt.plus(ROUND))) {
                return;
            }
            lastRoundAt = now;
            round(now);
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("A mail-in round failed ({}); the next round starts in a minute.", e.getClass().getSimpleName());
        }
    }

    private void round(Instant start) {
        try (Connection connection = mailbox.open()) {
            List<Mail> mails = connection.next(MAILS_PER_ROUND);
            Set<String> seen = new HashSet<>();
            for (Mail mail : mails) {
                Read read = connection.read(mail, MailInProcessor.MAX_SIZE);
                if (read == null) {
                    continue;
                }
                if (read.skipped()) {
                    log.warn("Mail-in skipped the email with UID {} in this round: {}.", mail.uid(), unread(read));
                }
                if (read.incoming() == null) {
                    String name = connection.uidValidity() + "/" + mail.uid();
                    seen.add(name);
                    unrecorded(connection, mail, name);
                    continue;
                }
                Destination destination = handle(read.incoming());
                if (destination != Destination.STAY) {
                    connection.move(mail, destination);
                }
            }
            skippedRounds.keySet().retainAll(seen);
            Instant now = clock.instant();
            if (lastDailyAt == null || !now.isBefore(lastDailyAt.plus(DAILY))) {
                daily(connection, now);
                lastDailyAt = now;
            }
        } catch (RoundFailure e) {
            failed(e.kind(), start);
            return;
        }
        failingSince = null;
        alerts.resolve(OpsAlerts.Kind.MAIL_IN_STALLED);
        status.succeeded();
    }

    /**
     * A mail whose bytes and header the server did not give (B7-AC-13): it has no key, so its skipped rounds are
     * counted here by UIDVALIDITY and UID, and the round after five of them moves it to the quarantine folder.
     */
    private void unrecorded(Connection connection, Mail mail, String name) {
        int skipped = skippedRounds.getOrDefault(name, 0);
        if (skipped < MailInProcessor.MAX_ATTEMPTS) {
            skippedRounds.put(name, skipped + 1);
            return;
        }
        connection.move(mail, Destination.QUARANTINE);
        skippedRounds.remove(name);
        processor.quarantinedUnrecorded();
        log.warn("Mail-in quarantined the email with UID {}: it could not be read in {} rounds.", mail.uid(),
                MailInProcessor.MAX_ATTEMPTS + 1);
    }

    /** What did not come of a skipped mail and how, for the log: no text of the server (B7-AC-15). */
    private static String unread(Read read) {
        if (read.raw() == null) {
            return "its header did not come (" + read.header() + ")";
        }
        if (read.header() == null) {
            return "its raw bytes did not come (" + read.raw() + ")";
        }
        return "its raw bytes (" + read.raw() + ") and its header (" + read.header() + ") did not come";
    }

    /**
     * One email; an app or database error leaves it in the incoming folder for the next round (S12-AC-09). A stack
     * that overflowed on a deeply built email is such an error too, so one email cannot stop the thread.
     */
    private Destination handle(Incoming incoming) {
        try {
            return processor.handle(incoming);
        } catch (RoundFailure e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("A received email failed ({}); it is tried again in the next round.", e.getClass().getSimpleName());
            return Destination.STAY;
        }
    }

    /** Once a day: the old mail of the mailbox goes (S12-AC-43), and a quiet day resolves the quarantine alert. */
    private void daily(Connection connection, Instant now) {
        int deleted = connection.deleteReceivedBefore(now.minus(KEPT));
        if (deleted > 0) {
            log.info("Mail-in deleted {} emails older than 90 days from the processed and quarantine folders.",
                    deleted);
        }
        Optional<Instant> lastQuarantine = records.lastQuarantinedAt();
        if (lastQuarantine.isEmpty() || lastQuarantine.get().isBefore(now.minus(QUARANTINE_QUIET))) {
            alerts.resolve(OpsAlerts.Kind.MAIL_IN_QUARANTINED);
        }
    }

    private void failed(FailureKind kind, Instant start) {
        status.failed(kind);
        metrics.roundFailed(kind);
        log.warn("A mail-in round failed ({}); the next round tries again in a minute.", kind);
        if (failingSince == null) {
            failingSince = start;
        }
        if (clock.instant().isAfter(failingSince.plus(STALLED_AFTER))) {
            alerts.raise(OpsAlerts.Kind.MAIL_IN_STALLED);
        }
    }
}
