package io.popdesk.helpdesk.mailin;

import io.popdesk.notification.CleanupJob;
import io.popdesk.notification.SentMailLookup;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The cleanup job {@code mail-record-cleanup} (S12-AC-33, MAIL-7, PII-6): once a day the received-mail records first
 * seen over 90 days ago, open ones too, and the sent-mail records of email sent over 90 days ago. It runs with
 * receiving email off as well, so records an earlier setup left do not stay (design.md 0절 15 ⑤).
 */
@Component
class MailRecordCleanup implements CleanupJob {

    static final String NAME = "mail-record-cleanup";

    /** How long both records are kept (privacy.md). */
    static final Duration KEPT = Duration.ofDays(90);

    private final MailReceivedRepository received;
    private final SentMailLookup sent;

    MailRecordCleanup(MailReceivedRepository received, SentMailLookup sent) {
        this.received = received;
        this.sent = sent;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofDays(1);
    }

    @Override
    public long run(Instant now) {
        Instant before = now.minus(KEPT);
        return (long) received.deleteSeenBefore(before) + sent.deleteSentBefore(before);
    }
}
