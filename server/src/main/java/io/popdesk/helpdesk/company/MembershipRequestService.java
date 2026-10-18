package io.popdesk.helpdesk.company;

import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.account.AccountService;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.MembershipRequestRepository.RequestRow;
import io.popdesk.helpdesk.company.MembershipRequestRepository.State;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership requests as an admin handles them (MGR-6, S2-AC-33 to S2-AC-38): the list of the open
 * ones, approving, and rejecting. Only admins reach this; the security filter answers the others.
 *
 * <p>A decision locks the request row before it reads its state, so of an approval and a rejection at
 * the same time one finds the request closed (S2-AC-37). The account a request is about hears nothing
 * at any step (S2-AC-40).
 */
@Service
public class MembershipRequestService {

    /** An open request, with the account that has the email as it is now. */
    public record Pending(long id, String companyCode, String companyName, long requesterId, String requesterName,
            String requesterEmail, String name, String email, long accountId, String accountName, Role accountRole,
            AccountStatus accountStatus, boolean invitationCancelled, Instant requestedAt) {}

    private final MembershipRequestRepository requests;
    private final CompanyRepository companies;
    private final CompanyService companyService;
    private final AccountRepository accounts;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;

    MembershipRequestService(MembershipRequestRepository requests, CompanyRepository companies,
            CompanyService companyService, AccountRepository accounts, AuditLog audit, Transactions transactions,
            Clock clock) {
        this.requests = requests;
        this.companies = companies;
        this.companyService = companyService;
        this.accounts = accounts;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * One page of the open requests, oldest first; of one company when {@code companyCode} is given
     * (S2-AC-33). A code that names no company is 400 {@code UNKNOWN_VALUE}; the code of a company of any
     * status narrows.
     */
    @Transactional(readOnly = true)
    public Paged<Pending> list(Viewer viewer, String companyCode, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Long companyId = companyCode == null ? null : companies.findByCode(viewer, companyCode)
                .orElseThrow(() -> ApiException.invalid("companyCode", "UNKNOWN_VALUE")).id();
        return request.of(requests.listPending(companyId, request.offset(), request.size()),
                requests.countPending(companyId));
    }

    /**
     * Approves a request: the account becomes a member of the company, as adding the customer on the
     * console does, and the request is closed (S2-AC-34). The account itself is not changed: a cancelled
     * invitation stays cancelled. Approving an approved request again changes nothing; one that was
     * rejected or withdrawn is 409 (S2-AC-37). An account that is no customer, or an {@code ARCHIVED}
     * company, is 400 and the request stays open (S2-AC-36).
     */
    public void approve(Viewer viewer, AuditLog.Actor admin, long id) {
        transactions.run(() -> {
            RequestRow request = requests.lockById(id).orElseThrow(ApiException::notFound);
            if (request.state() == State.APPROVED) {
                return;
            }
            if (request.state() != State.PENDING) {
                throw closed();
            }
            CompanyRow company = companies.findByCode(viewer, request.companyCode())
                    .orElseThrow(ApiException::notFound);
            AccountRow account = accounts.findById(request.accountId()).orElseThrow(ApiException::notFound);
            AccountService.refuseAnonymized(account);
            if (account.role() != Role.CUSTOMER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_CUSTOMER", "The account is not a customer.");
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw CompanyService.notActive();
            }
            companyService.admit(admin, company, account.id());
        });
    }

    /**
     * Rejects a request, whatever the account's role and the company's status (S2-AC-35, S2-AC-36).
     * Rejecting a rejected request again changes nothing; one that was approved or withdrawn is 409.
     */
    public void reject(AuditLog.Actor admin, long id) {
        transactions.run(() -> {
            RequestRow request = requests.lockById(id).orElseThrow(ApiException::notFound);
            if (request.state() == State.REJECTED) {
                return;
            }
            if (request.state() != State.PENDING) {
                throw closed();
            }
            requests.updateClosed(request.id(), State.REJECTED, admin.accountId(), now());
            audit.record(admin, AuditAction.MEMBERSHIP_REQUEST_REJECTED,
                    CompanyService.target(request.companyCode(), request.accountId()),
                    Map.of("email", request.email()));
        });
    }

    /**
     * Puts the anonymous email and name of an anonymized account into its requests, in the caller's
     * transaction (S10A-AC-10): the email and name the manager typed are copies of the person.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void anonymize(long accountId, String email, String name) {
        requests.updateAnonymized(accountId, email, name);
    }

    private static ApiException closed() {
        return ApiException.conflict("REQUEST_CLOSED", "This request is closed.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
