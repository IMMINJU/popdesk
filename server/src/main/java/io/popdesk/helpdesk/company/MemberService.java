package io.popdesk.helpdesk.company;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.InvitationService;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyRepository.MemberRef;
import io.popdesk.helpdesk.company.CompanyRepository.OwnMembership;
import io.popdesk.helpdesk.company.MembershipRequestRepository.RequestRow;
import io.popdesk.helpdesk.company.MembershipRequestRepository.State;
import io.popdesk.notification.AdminInbox;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a customer manager does for a company (MGR-2 to MGR-4, S2-AC-08 to S2-AC-32): the member list,
 * inviting, taking a row out, and sending an invitation again.
 *
 * <p>Every method starts with {@link #manager}: whether the caller is a manager is read from the
 * membership on every request, never kept in the session (S2-AC-11). The order is the one of
 * conventions: the limit is counted, then 404, 403, the input (400), the state (400, 409).
 *
 * <p>An email that already has an account must not show: inviting it answers like a new email, and its
 * row in the list looks like a new account's row (S2-AC-15, S2-AC-16). So nothing here answers, or
 * logs, by whether the account was there.
 */
@Service
public class MemberService {

    /** {@code INVITED} rows a company's member list may hold: the next invitation is refused (S2-AC-21). */
    static final int MAX_INVITED = 100;

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    /** A row of the member list. {@code id} names the row; it is not an account ID (S2-AC-10). */
    public record Row(UUID id, String name, String email, AccountStatus status, boolean manager) {}

    private final CompanyRepository companies;
    private final MembershipRequestRepository requests;
    private final AccountRepository accounts;
    private final InvitationService invitations;
    private final AdminInbox inbox;
    private final AccountRateLimit limit;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;

    MemberService(CompanyRepository companies, MembershipRequestRepository requests, AccountRepository accounts,
            InvitationService invitations, AdminInbox inbox, AccountRateLimit limit, AuditLog audit,
            Transactions transactions, Clock clock) {
        this.companies = companies;
        this.requests = requests;
        this.accounts = accounts;
        this.invitations = invitations;
        this.inbox = inbox;
        this.limit = limit;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The company, for a caller who is a manager of it: 404 when the caller does not belong to it or
     * there is none, 403 when the caller belongs to it without the mark (S2-AC-09). The one check of the
     * four member endpoints. With {@code lock}, for a write, the caller's membership stays locked shared
     * until the end of the transaction, so the mark cannot be taken away between this check and the
     * write (S2-AC-11).
     */
    private CompanyRow manager(Viewer viewer, String code, boolean lock) {
        OwnMembership own = companies.findOwnMembership(viewer, code, lock).orElseThrow(ApiException::notFound);
        if (!own.manager()) {
            throw ApiException.forbidden();
        }
        return own.company();
    }

    /** One page of the member list, in email order, for a company of any status (S2-AC-08, S2-AC-12). */
    @Transactional(readOnly = true)
    public Paged<Row> list(Viewer viewer, String code, int page, int size) {
        CompanyRow company = manager(viewer, code, false);
        PageRequest request = PageRequest.of(page, size);
        return request.of(requests.listRows(company.id(), request.offset(), request.size()),
                requests.countRows(company.id()));
    }

    /**
     * Invites an email to the company (S2-AC-13, S2-AC-14). A new email becomes an {@code INVITED}
     * customer of this company with the invitation mail an admin's invitation sends. An email that has
     * an account, whatever its role and status, leaves that account as it is and becomes a membership
     * request for an admin, with an item in the admins' inbox. Both end the same way for the caller.
     *
     * <p>The company's status and the limit of 100 are checked before the email is looked up, so their
     * answers do not depend on it. 409 {@code ALREADY_LISTED} only for an email the caller's list
     * already shows (S2-AC-17).
     */
    public void invite(Viewer viewer, AuditLog.Actor manager, String code, String email, String name) {
        limit.count(AccountRateLimit.Action.MEMBER_INVITATION, viewer.accountId());
        List<ApiError.FieldError> errors = new ArrayList<>();
        String cleanEmail = InvitationService.cleanEmail(email, errors);
        String cleanName = name(name, errors);
        transactions.run(() -> {
            CompanyRow company = manager(viewer, code, true);
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw CompanyService.notActive();
            }
            if (requests.countInvited(company.id()) >= MAX_INVITED) {
                throw ApiException.conflict("TOO_MANY_PENDING", "Too many invitations of this company are waiting.");
            }
            if (invitations.inviteCustomer(manager, cleanEmail, cleanName, company).isEmpty()) {
                request(manager, company, cleanEmail, cleanName);
            }
        });
        log.info("A manager's invitation to company {} was taken.", code);
    }

    /** The membership request for the account that has this email, or 409 when the list shows it already. */
    private void request(AuditLog.Actor manager, CompanyRow company, String cleanEmail, String cleanName) {
        AccountRow existing = accounts.findByEmail(cleanEmail)
                .orElseThrow(() -> new IllegalStateException("The account of a taken email is gone"));
        Instant now = now();
        if (companies.isMember(company.id(), existing.id()) || requests.insert(company.id(), existing.id(),
                manager.accountId(), cleanEmail, cleanName, now).isEmpty()) {
            throw ApiException.conflict("ALREADY_LISTED", "This email is in the member list already.");
        }
        inbox.membershipRequested(company.code());
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("email", cleanEmail);
        changes.put("name", cleanName);
        audit.record(manager, AuditAction.MEMBERSHIP_REQUESTED, CompanyService.target(company.code(), existing.id()),
                changes);
    }

    /**
     * Takes a row out of the member list (S2-AC-24 to S2-AC-28). A member loses this membership only;
     * when that was the last one of an account that has not accepted, its invitation is cancelled too.
     * A membership request is withdrawn. The caller's own row and another manager's are refused.
     *
     * <p>The row is read without a lock and the membership deleted only while it carries no mark, so two
     * managers who take each other out at the same time do not wait for each other's membership.
     */
    public void remove(Viewer viewer, AuditLog.Actor manager, String code, String rowId) {
        transactions.run(() -> {
            CompanyRow company = manager(viewer, code, true);
            UUID id = rowId(rowId);
            MemberRef member = companies.findMemberByRowId(company.id(), id).orElse(null);
            if (member == null) {
                RequestRow request = requests.findPendingByRowId(company.id(), id)
                        .orElseThrow(ApiException::notFound);
                if (!requests.updateClosed(request.id(), State.WITHDRAWN, viewer.accountId(), now())) {
                    throw ApiException.notFound();
                }
                audit.record(manager, AuditAction.MEMBERSHIP_REQUEST_WITHDRAWN,
                        CompanyService.target(company.code(), request.accountId()), Map.of("email", request.email()));
                return;
            }
            if (member.accountId() == viewer.accountId()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "OWN_ACCOUNT", "This cannot be done to your own account.");
            }
            if (member.manager()) {
                throw memberIsManager();
            }
            if (!companies.deleteMemberUnlessManager(company.id(), member.accountId())) {
                // Marked or taken out since the row was read.
                throw companies.findMemberByRowId(company.id(), id).isPresent() ? memberIsManager()
                        : ApiException.notFound();
            }
            audit.record(manager, AuditAction.CUSTOMER_REMOVED,
                    CompanyService.target(company.code(), member.accountId()), Map.of("member", false));
            // Counted after the membership went. A membership request elsewhere is no membership.
            if (companies.countMemberships(member.accountId()) == 0) {
                invitations.cancelOpen(manager, member.accountId());
            }
        });
    }

    /**
     * Sends the invitation of a row again (S2-AC-29 to S2-AC-31): for a member who has not accepted and
     * belongs to this company only. The row of a membership request answers the same and does nothing.
     * Any other member is one 400, whatever the reason. Counts with the invitations (S2-AC-20).
     */
    public void resend(Viewer viewer, AuditLog.Actor manager, String code, String rowId) {
        limit.count(AccountRateLimit.Action.MEMBER_INVITATION, viewer.accountId());
        transactions.run(() -> {
            CompanyRow company = manager(viewer, code, true);
            UUID id = rowId(rowId);
            MemberRef member = companies.findMemberByRowId(company.id(), id).orElse(null);
            if (member == null) {
                requests.findPendingByRowId(company.id(), id).orElseThrow(ApiException::notFound);
                return;
            }
            if (companies.countMemberships(member.accountId()) != 1
                    || !invitations.resendOpen(manager, member.accountId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVITATION_NOT_RESENDABLE",
                        "This invitation cannot be sent again from here.");
            }
        });
    }

    /** The row ID of a path; text that is no UUID names no row (S2-AC-32). */
    private static UUID rowId(String received) {
        try {
            return UUID.fromString(received);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
    }

    /**
     * The name of a manager's invitation: the rules of an admin's, and no control character such as a
     * line break or a tab, because the name goes into the invitation mail (S2-AC-53).
     */
    private static String name(String received, List<ApiError.FieldError> errors) {
        String name = TextInput.required("name", received, InvitationService.NAME_MAX, errors);
        if (name != null && name.codePoints().anyMatch(Character::isISOControl)) {
            errors.add(new ApiError.FieldError("name", "INVALID_FORMAT"));
            return null;
        }
        return name;
    }

    private static ApiException memberIsManager() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_IS_MANAGER", "This member is a manager of the company.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
