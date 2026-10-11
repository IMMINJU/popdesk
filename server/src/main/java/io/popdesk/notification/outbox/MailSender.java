package io.popdesk.notification.outbox;

import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.notification.InvitationLinks;
import io.popdesk.notification.IssueAccess;
import io.popdesk.notification.IssueFacts;
import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.PasswordResetLinks;
import io.popdesk.notification.Recipients;
import io.popdesk.notification.Recipients.Recipient;
import io.popdesk.notification.Recipients.Status;
import io.popdesk.notification.outbox.MailText.Message;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The mail channel. Just before each email it reads from the database who receives it and what it says
 * (MAIL-5, S0-AC-66), and drops the email when the account is no longer {@code ACTIVE} ({@code INVITED}
 * for an invitation) or may no longer see the issue (S0-AC-69). Without SMTP it writes one log line, the
 * address masked, in place of the email (S0-AC-73).
 *
 * <p>An invitation or password reset email gets a new token when it is first tried, since the database
 * keeps only the token's hash; retries of that row send the same link. The link is kept in memory only
 * and never logged; after a restart the next attempt makes a new one.
 */
@Component
class MailSender implements Sender {

    private static final Logger log = LoggerFactory.getLogger(MailSender.class);

    private final Recipients recipients;
    private final IssueFacts issues;
    private final IssueAccess access;
    private final InvitationLinks invitations;
    private final PasswordResetLinks passwordResets;
    private final PublicAddress address;
    private final SmtpMail smtp;
    private final Map<Long, String> links = new ConcurrentHashMap<>();

    MailSender(Recipients recipients, IssueFacts issues, IssueAccess access, InvitationLinks invitations,
            PasswordResetLinks passwordResets, PublicAddress address, SmtpMail smtp) {
        this.recipients = recipients;
        this.issues = issues;
        this.access = access;
        this.invitations = invitations;
        this.passwordResets = passwordResets;
        this.address = address;
        this.smtp = smtp;
    }

    @Override
    public String channel() {
        return OutboxRepository.MAIL;
    }

    @Override
    public Attempt attempt(OutboxRow row) {
        MailKind kind = MailKind.valueOf(row.kind());
        Recipient recipient = row.recipientAccountId() == null ? null
                : recipients.find(row.recipientAccountId()).orElse(null);
        Status wanted = kind == MailKind.INVITATION ? Status.INVITED : Status.ACTIVE;
        if (recipient == null || recipient.status() != wanted) {
            return Attempt.dropped();
        }
        Issue issue = null;
        if (row.issueId() != null) {
            issue = issues.find(row.issueId()).orElse(null);
            if (issue == null || !access.canSee(row.recipientAccountId(), row.issueId())) {
                return Attempt.dropped();
            }
        }
        if (!smtp.enabled()) {
            log.info("No SMTP server is set up, so the {} email to {} was not sent.", kind, masked(recipient.email()));
            return Attempt.dropped();
        }
        Optional<Message> message = switch (kind) {
            case INVITATION -> link(row, invitations::renew).map(link -> MailText.invitation(recipient.locale(),
                    recipient.name(), link));
            case PASSWORD_RESET -> link(row, passwordResets::renew).map(link -> MailText.passwordReset(
                    recipient.locale(), recipient.name(), link));
            case STAFF_COMMENT -> Optional.of(MailText.staffComment(recipient.locale(), issue,
                    address.origin(App.PORTAL) + "/issues/" + issue.key()));
            // In the contract of slice 3; nothing queues this kind until the slice is built.
            case ISSUE_FILED_ON_BEHALF -> throw new UnsupportedOperationException("Not built yet: " + kind);
        };
        if (message.isEmpty()) {
            return Attempt.dropped();
        }
        return smtp.send(recipient.email(), message.get());
    }

    @Override
    public void forget(long rowId) {
        links.remove(rowId);
    }

    /**
     * The link of an earlier attempt of this row, or a new one from {@code renew}, which takes the
     * account ID; empty when the invitation or the reset link is gone.
     */
    private Optional<String> link(OutboxRow row, LongFunction<Optional<String>> renew) {
        String kept = links.get(row.id());
        if (kept != null) {
            return Optional.of(kept);
        }
        Optional<String> link = renew.apply(row.recipientAccountId());
        link.ifPresent(l -> links.put(row.id(), l));
        return link;
    }

    /** The address with everything after its first character masked: {@code a***@example.com} (PII-2). */
    static String masked(String email) {
        int at = email.lastIndexOf('@');
        return email.isEmpty() ? "***" : email.charAt(0) + "***" + (at < 0 ? "" : email.substring(at));
    }
}
