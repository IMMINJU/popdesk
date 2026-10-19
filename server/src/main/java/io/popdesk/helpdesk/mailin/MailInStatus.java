package io.popdesk.helpdesk.mailin;

import io.popdesk.notification.MailInState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The mail-in part of the system status (S12-AC-36, design.md 0절 3), and what the email popdesk sends needs of it.
 * The rounds' last success and failures are counted in memory since the app started; the emails to try again and the
 * quarantined ones of the last 90 days are read from the records, so they survive a restart. Times are of the app's
 * clock.
 */
@Component
class MailInStatus implements MailInState {

    /** How far back quarantined emails are counted. */
    static final Duration QUARANTINE_WINDOW = Duration.ofDays(90);

    private final MailInSetup setup;
    private final MailReceivedRepository records;
    private final Clock clock;

    private Instant lastSuccessAt;
    private int roundFailures;
    private FailureKind lastFailureKind;
    private Instant lastFailureAt;

    MailInStatus(MailInSetup setup, MailReceivedRepository records, Clock clock) {
        this.setup = setup;
        this.records = records;
        this.clock = clock;
    }

    /** A round read the inbox folder through and ended now. */
    synchronized void succeeded() {
        lastSuccessAt = clock.instant();
    }

    /** A round failed now, in this way. */
    synchronized void failed(FailureKind kind) {
        roundFailures++;
        lastFailureKind = kind;
        lastFailureAt = clock.instant();
    }

    @Override
    public Snapshot snapshot() {
        MailInSettings settings = setup.settings();
        // Off, the counts are 0 (conventions.md "Receiving email"): nothing is tried again, and a sandbox's copy of
        // the records is not its own to look after.
        int retrying = settings.enabled() ? records.countRetrying() : 0;
        int quarantined = settings.enabled()
                ? records.countQuarantinedSince(clock.instant().minus(QUARANTINE_WINDOW)) : 0;
        synchronized (this) {
            return new Snapshot(settings.enabled(), settings.offReason(), lastSuccessAt, roundFailures,
                    lastFailureKind, lastFailureAt, retrying, quarantined);
        }
    }

    @Override
    public Optional<String> replyTo() {
        MailInSettings settings = setup.settings();
        return settings.enabled() ? Optional.of(settings.inboundAddress()) : Optional.empty();
    }

    @Override
    public Optional<String> messageIdDomain() {
        return Optional.ofNullable(setup.settings().inboundDomain());
    }
}
