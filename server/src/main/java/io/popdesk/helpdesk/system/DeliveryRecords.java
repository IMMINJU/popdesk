package io.popdesk.helpdesk.system;

import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.notification.DeliveryAudit;
import io.popdesk.notification.TestMailLimit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the notification module asks of helpdesk when an admin sends a failed row again or sends a test
 * email (OPS-3): the audit entry, with the admin as the person, and the count against the admin's
 * limit. The entries have no target; what they say is in the changes.
 */
@Service
class DeliveryRecords implements DeliveryAudit, TestMailLimit {

    private final AccountRepository accounts;
    private final AuditLog audit;
    private final AccountRateLimit rateLimit;
    private final Transactions transactions;

    DeliveryRecords(AccountRepository accounts, AuditLog audit, AccountRateLimit rateLimit,
            Transactions transactions) {
        this.accounts = accounts;
        this.audit = audit;
        this.rateLimit = rateLimit;
        this.transactions = transactions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void resent(long actorAccountId, String channel, String kind) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("channel", channel);
        changes.put("kind", kind);
        audit.record(actor(actorAccountId), AuditAction.DELIVERY_RESENT, null, changes);
    }

    @Override
    public void testMailSent(long actorAccountId, boolean ok, String errorKind) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("ok", ok);
        if (!ok) {
            changes.put("errorKind", errorKind);
        }
        transactions.run(() -> audit.record(actor(actorAccountId), AuditAction.TEST_MAIL_SENT, null, changes));
    }

    @Override
    public void count(long accountId) {
        rateLimit.count(AccountRateLimit.Action.TEST_MAIL, accountId);
    }

    private AuditLog.Actor actor(long accountId) {
        return accounts.findById(accountId).map(a -> new AuditLog.Actor(a.id(), a.name(), a.email()))
                .orElseThrow(() -> new IllegalStateException("The signed-in account is gone"));
    }
}
