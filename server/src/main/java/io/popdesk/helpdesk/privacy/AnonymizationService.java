package io.popdesk.helpdesk.privacy;

import io.popdesk.ApiException;
import io.popdesk.InstallationLocale;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.AccountService;
import io.popdesk.helpdesk.account.AccountService.Suspension;
import io.popdesk.helpdesk.account.AnonymousValues;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.audit.AuditRechain;
import io.popdesk.helpdesk.audit.AuditRechain.Recalculation;
import io.popdesk.helpdesk.company.MembershipRequestService;
import io.popdesk.helpdesk.mailin.MailReceivedRepository;
import io.popdesk.helpdesk.photo.PhotoRepository;
import io.popdesk.notification.IssueInbox;
import io.popdesk.notification.MailQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Anonymizing an account (PII-4, PII-5, S10A-AC-08 to S10A-AC-21, design.md 4절). It cannot be undone:
 * the name and email become the anonymous ones, the account {@code SUSPENDED}, and what holds the
 * person's email or name goes or gets the anonymous values, each part by the package that owns it; what
 * the person wrote stays. All of it is one transaction, so a failure anywhere leaves everything as it
 * was (S10A-AC-19).
 *
 * <p>One anonymization runs at a time (409 {@code ANONYMIZATION_RUNNING}, S10A-AC-21): it holds the lock
 * of the audit recalculation from before its transaction starts until after it ends.
 */
@Service
public class AnonymizationService {

    private static final Logger log = LoggerFactory.getLogger(AnonymizationService.class);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final AuditRechain rechain;
    private final PhotoRepository photos;
    private final MailQueue mail;
    private final IssueInbox inbox;
    private final MembershipRequestService requests;
    private final MailReceivedRepository receivedMail;
    private final Transactions transactions;
    private final InstallationLocale locale;

    AnonymizationService(AccountRepository accounts, AccountService accountService, AuditRechain rechain,
            PhotoRepository photos, MailQueue mail, IssueInbox inbox, MembershipRequestService requests,
            MailReceivedRepository receivedMail, Transactions transactions, InstallationLocale locale) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.rechain = rechain;
        this.photos = photos;
        this.mail = mail;
        this.inbox = inbox;
        this.requests = requests;
        this.receivedMail = receivedMail;
        this.transactions = transactions;
        this.locale = locale;
    }

    /**
     * Anonymizes the account, in this order (design.md 4절): the lock (409 when another anonymization
     * holds it), the account (404; account 0, popdesk itself, has no row), an anonymized account (400
     * {@code ACCOUNT_ANONYMIZED}, before the transaction). Then one transaction:
     *
     * <ol>
     *   <li>the first part of the audit recalculation, without the chain's head or the account's row;
     *   <li>the account's part ({@link AccountService#anonymize}): the locks, the checks of
     *       {@code ACCOUNT_ANONYMIZED}, {@code OWN_ACCOUNT}, and {@code LAST_ADMIN}, which roll the first
     *       part back too, the account row, its invitation and reset link, the cleared assignments with
     *       {@code clearAssignments}, {@code ACCOUNT_ANONYMIZED}, and its sessions;
     *   <li>the profile photo, the emails queued to it, its inbox, the copies of its email and name in
     *       membership requests, and its address in the records of the email it sent (S12-AC-31);
     *   <li>the second part of the recalculation, last, which holds the chain's head until the commit and
     *       appends {@code AUDIT_RECHAINED} before {@code ACCOUNT_ANONYMIZED}. When either part met an entry or
     *       a head that does not hold, it is 409 {@code AUDIT_CHAIN_BROKEN} and everything rolls back
     *       (S10A-AC-37).
     * </ol>
     *
     * <p>A retry after a lock timeout ({@link Transactions#run}) starts again from the first part.
     *
     * @return the anonymized account as it is now, and how many issues it was taken off as assignee
     */
    public Suspension anonymize(CurrentAccount admin, long accountId, boolean clearAssignments) {
        Suspension done = rechain.alone(() -> {
            AccountService.refuseAnonymized(accounts.findById(accountId).orElseThrow(ApiException::notFound));
            AnonymousValues anonymous = AnonymousValues.of(accountId, locale.language());
            return transactions.run(() -> {
                Recalculation recalculation = rechain.start(accountId, anonymous.name(), anonymous.email());
                Suspension suspension = accountService.anonymize(admin, accountId, clearAssignments, anonymous);
                photos.delete(accountId);
                mail.dropTo(accountId);
                inbox.deleteOfAccount(accountId);
                requests.anonymize(accountId, anonymous.email(), anonymous.name());
                receivedMail.clearSender(accountId);
                rechain.finish(recalculation, admin.actor());
                AccountRow now = accounts.findById(accountId).orElseThrow();
                return new Suspension(now, suspension.clearedIssues());
            });
        }, () -> ApiException.conflict("ANONYMIZATION_RUNNING", "Another account is being anonymized."));
        log.info("Account {} is anonymized.", accountId);
        return done;
    }
}
