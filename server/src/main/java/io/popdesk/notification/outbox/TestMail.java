package io.popdesk.notification.outbox;

import io.popdesk.ApiException;
import io.popdesk.InstallationZone;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.notification.DeliveryAudit;
import io.popdesk.notification.Recipients;
import io.popdesk.notification.Recipients.Recipient;
import io.popdesk.notification.TestMailLimit;
import io.popdesk.notification.outbox.Attempt.Outcome;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The test email (OPS-3, S4B-AC-22 to S4B-AC-28): one email to the admin who asks, sent within the
 * request and not through the outbox, so the admin sees at once whether the SMTP settings work and what
 * the server answered. It leaves through {@link SmtpMail} as every email does: in a sandbox it goes to
 * the sandbox address, and it counts toward the 50 an hour its address gets.
 *
 * <p>The limit of 5 a minute is counted first, before the channel is looked at (conventions "Order of
 * checks"). The email is sent outside any transaction, since the SMTP server may take as long as its
 * timeouts; the audit entry is written afterwards, whether the email went out or not, and holds the
 * kind of the error only. What the server answered goes to the caller and nowhere else: not to the log
 * and not to the audit log (S4B-AC-27, S4B-AC-38).
 */
@Service
public class TestMail {

    private static final Logger log = LoggerFactory.getLogger(TestMail.class);

    /** The time in the email: to the minute, then the time zone's ID in brackets. */
    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    /** What came of a test email. {@code errorKind} is null when {@code ok}; {@code serverReply} may be null. */
    public record Result(boolean ok, String errorKind, String serverReply) {}

    private final TestMailLimit limit;
    private final SmtpMail smtp;
    private final Recipients recipients;
    private final DeliveryAudit audit;
    private final PublicAddress address;
    private final Clock clock;
    private final ZoneId zone;

    TestMail(TestMailLimit limit, SmtpMail smtp, Recipients recipients, DeliveryAudit audit, PublicAddress address,
            Clock clock, InstallationZone zone) {
        this.limit = limit;
        this.smtp = smtp;
        this.recipients = recipients;
        this.audit = audit;
        this.address = address;
        this.clock = clock;
        // The zone due dates are judged in (S6A-AC-72); a wrong value lets no request through.
        this.zone = zone.zone();
    }

    /** @param accountId the admin who asks; the email goes to this account's address */
    public Result send(long accountId) {
        limit.count(accountId);
        if (!smtp.enabled()) {
            throw ApiException.channelDisabled();
        }
        Recipient me = recipients.find(accountId)
                .orElseThrow(() -> new IllegalStateException("The signed-in account is gone"));
        String sentAt = SENT_AT.withZone(zone).format(clock.instant()) + " (" + zone.getId() + ")";
        SmtpMail.Result sent = smtp.sendForReply(accountId, me.email(),
                MailText.test(me.locale(), address.origin(App.CONSOLE), sentAt));
        boolean ok = sent.attempt().outcome() == Outcome.SENT;
        String errorKind = ok ? null : sent.attempt().errorKind();
        audit.testMailSent(accountId, ok, errorKind);
        log.info("Account {} sent a test email: {}.", accountId, ok ? "it went out" : errorKind);
        return new Result(ok, errorKind, sent.serverReply());
    }
}
