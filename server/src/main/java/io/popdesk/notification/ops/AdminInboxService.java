package io.popdesk.notification.ops;

import io.popdesk.notification.AdminInbox;
import io.popdesk.notification.MailKind;
import io.popdesk.notification.MailQueue;
import io.popdesk.notification.Recipients;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link AdminInbox} on the table {@code notification}, with the emails through the {@link MailQueue}. */
@Service
class AdminInboxService implements AdminInbox {

    /** The inbox item's {@code kind}, as the contract names it. */
    static final String MEMBERSHIP_REQUESTED = "MEMBERSHIP_REQUESTED";

    /** The inbox item's {@code kind} of an SLA summary (S8B-AC-14). */
    static final String SLA_RECALCULATED = "SLA_RECALCULATED";

    private final OpsRepository ops;
    private final Recipients recipients;
    private final MailQueue mail;
    private final Clock clock;

    AdminInboxService(OpsRepository ops, Recipients recipients, MailQueue mail, Clock clock) {
        this.ops = ops;
        this.recipients = recipients;
        this.mail = mail;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void membershipRequested(String companyCode, long aboutAccountId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (long admin : recipients.activeAdmins()) {
            ops.insertInbox(admin, MEMBERSHIP_REQUESTED, companyCode, now);
            if (admin != aboutAccountId) {
                mail.queueAboutCompany(MailKind.MEMBERSHIP_REQUESTED, admin, companyCode);
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void slaRecalculated(int becameBreached, int becameWarning, int released) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (long admin : recipients.activeAdmins()) {
            ops.insertInboxSlaSummary(admin, SLA_RECALCULATED, becameBreached, becameWarning, released, now);
        }
    }
}
