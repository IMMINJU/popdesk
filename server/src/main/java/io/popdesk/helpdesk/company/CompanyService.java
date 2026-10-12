package io.popdesk.helpdesk.company;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyRepository.MemberRow;
import io.popdesk.helpdesk.company.CompanyRepository.StaffRow;
import io.popdesk.helpdesk.company.MembershipRequestRepository.RequestRow;
import io.popdesk.helpdesk.company.MembershipRequestRepository.State;
import io.popdesk.helpdesk.issue.AssigneeClearing;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer companies (PROJ-1 to PROJ-3), memberships and agent assignments (PROJ-4), and the company
 * lists (PROJ-5). The order of checks is the one of conventions: the caller's role is checked by the
 * security filter, then the target is looked up in the caller's scope (404), then the input (400), then
 * the state (400, 409). Each change writes its audit row in its own transaction (S0-AC-83, S1A-AC-62).
 *
 * <p>A company that is not {@code ACTIVE} takes no new issues. An {@code ARCHIVED} one is final: its
 * status and names no longer change and nobody is added to it, but people can still be taken out
 * (S1A-AC-45, S1A-AC-48).
 */
@Service
public class CompanyService {

    /** 2 to 10 capital letters and digits (D-12). Small letters are not turned into capitals. */
    static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,10}");
    static final int NAME_MAX = 100;

    private final CompanyRepository companies;
    private final MembershipRequestRepository requests;
    private final AccountRepository accounts;
    private final AssigneeClearing assignments;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;

    CompanyService(CompanyRepository companies, MembershipRequestRepository requests, AccountRepository accounts,
            AssigneeClearing assignments, AuditLog audit, Transactions transactions, Clock clock) {
        this.companies = companies;
        this.requests = requests;
        this.accounts = accounts;
        this.assignments = assignments;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Creates an {@code ACTIVE} company with its audit row in one transaction (S0-AC-29, S0-AC-83). */
    public CompanyRow create(AuditLog.Actor admin, String code, String name, String legalName) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String cleanName = TextInput.required("name", name, NAME_MAX, errors);
        String cleanCode = null;
        if (code == null || code.isEmpty()) {
            errors.add(new ApiError.FieldError("code", "REQUIRED"));
        } else if (!CODE.matcher(code).matches()) {
            errors.add(new ApiError.FieldError("code", "INVALID_FORMAT"));
        } else {
            cleanCode = code;
        }
        String cleanLegalName = TextInput.required("legalName", legalName, NAME_MAX, errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        String finalCode = cleanCode;
        return transactions.run(() -> {
            Instant now = now();
            long id = companies.insert(finalCode, cleanName, cleanLegalName, now).orElseThrow(() ->
                    ApiException.conflict("COMPANY_CODE_TAKEN", "Another company has this code."));
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("type", "COMPANY");
            target.put("code", finalCode);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("code", finalCode);
            changes.put("name", cleanName);
            changes.put("legalName", cleanLegalName);
            changes.put("status", CompanyStatus.ACTIVE.name());
            audit.record(admin, AuditAction.COMPANY_CREATED, target, changes);
            return new CompanyRow(id, finalCode, cleanName, cleanLegalName, CompanyStatus.ACTIVE, now);
        });
    }

    @Transactional(readOnly = true)
    public CompanyRow get(Viewer viewer, String code) {
        return companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public Paged<CompanyRow> list(Viewer viewer, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return request.of(companies.list(viewer, request.offset(), request.size()), companies.count(viewer));
    }

    /**
     * How many membership requests wait for an admin, by company code, for these companies (S2-AC-48);
     * a company without one is not in the map. Empty for an agent, who cannot list the requests
     * (S2-AC-33).
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> pendingRequests(Viewer viewer, List<CompanyRow> rows) {
        if (!viewer.admin()) {
            return Map.of();
        }
        Map<Long, Integer> byId = requests.countPendingByCompany(rows.stream().map(CompanyRow::id).toList());
        Map<String, Integer> byCode = new LinkedHashMap<>();
        for (CompanyRow row : rows) {
            Integer count = byId.get(row.id());
            if (count != null) {
                byCode.put(row.code(), count);
            }
        }
        return byCode;
    }

    /** The codes of the companies where the caller, a customer, is a manager (S2-AC-07). */
    @Transactional(readOnly = true)
    public Set<String> managedCodes(Viewer viewer) {
        return Set.copyOf(companies.listManagedCodes(viewer));
    }

    /** Customers or agents of a company: the company first (404), then the page (400). */
    @Transactional(readOnly = true)
    public Paged<MemberRow> members(Viewer viewer, String code, Role role, int page, int size) {
        CompanyRow company = get(viewer, code);
        PageRequest request = PageRequest.of(page, size);
        return request.of(companies.listMembers(viewer, company.id(), role, request.offset(), request.size()),
                companies.countMembers(viewer, company.id(), role));
    }

    /**
     * Everyone who can be the assignee of an issue of the company, in name order (S3-AC-27): the
     * {@code ACTIVE} admins and the {@code ACTIVE} agents assigned to it. A company the caller, an agent,
     * is not assigned to is 404, like a code that does not exist.
     */
    @Transactional(readOnly = true)
    public List<StaffRow> assigneeCandidates(Viewer viewer, String code) {
        CompanyRow company = get(viewer, code);
        return companies.listAssignable(viewer, company.id());
    }

    /**
     * How many open issues of the company have the account as their assignee: what
     * {@link #unassignAgent} clears with {@code clearAssignments} (S3-AC-68). The company first, then the
     * account (404). An agent who is not assigned to the company is no error; the count is of the
     * issues, not of the assignment.
     */
    @Transactional(readOnly = true)
    public long openAssignments(Viewer viewer, String code, long accountId) {
        CompanyRow company = get(viewer, code);
        AccountRow account = accounts.findById(accountId).orElseThrow(ApiException::notFound);
        return assignments.count(viewer, account.id(), company.id());
    }

    /**
     * Changes the name, the legal name, or both (S1A-AC-43); a field left out keeps what the company has, and
     * one sent as null is {@code REQUIRED} (ADR D-44). The
     * code never changes: a request that sends one ({@code codeSent}) is 400. A 400 lists every invalid
     * field and saves nothing. Values the company already has are not audited.
     */
    public CompanyRow update(Viewer viewer, AuditLog.Actor admin, String code, PatchText name,
            PatchText legalName, boolean codeSent) {
        return transactions.run(() -> {
            CompanyRow company = companies.lockByCode(viewer, code).orElseThrow(ApiException::notFound);
            List<ApiError.FieldError> errors = new ArrayList<>();
            if (codeSent) {
                errors.add(new ApiError.FieldError("code", "NOT_ALLOWED"));
            }
            String newName = name.sent() ? name.required("name", NAME_MAX, errors) : company.name();
            String newLegalName = legalName.sent() ? legalName.required("legalName", NAME_MAX, errors)
                    : company.legalName();
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw archived();
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            if (!newName.equals(company.name())) {
                changes.put("name", change(company.name(), newName));
            }
            if (!newLegalName.equals(company.legalName())) {
                changes.put("legalName", change(company.legalName(), newLegalName));
            }
            if (changes.isEmpty()) {
                return company;
            }
            companies.updateNames(company.id(), newName, newLegalName);
            audit.record(admin, AuditAction.COMPANY_UPDATED, target(company.code()), changes);
            return new CompanyRow(company.id(), company.code(), newName, newLegalName, company.status(),
                    company.createdAt());
        });
    }

    /**
     * Moves a company between {@code ACTIVE} and {@code SUSPENDED}, or from either to {@code ARCHIVED}
     * (S1A-AC-44). The status it has changes nothing. An {@code ARCHIVED} company is refused, also for
     * {@code ARCHIVED} again (S1A-AC-45).
     */
    public CompanyRow changeStatus(Viewer viewer, AuditLog.Actor admin, String code, CompanyStatus status) {
        return transactions.run(() -> {
            CompanyRow company = companies.lockByCode(viewer, code).orElseThrow(ApiException::notFound);
            if (status == null) {
                throw ApiException.invalid("status", "REQUIRED");
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw archived();
            }
            if (company.status() == status) {
                return company;
            }
            companies.updateStatus(company.id(), status);
            audit.record(admin, AuditAction.COMPANY_STATUS_CHANGED, target(company.code()),
                    Map.of("status", change(company.status().name(), status.name())));
            return new CompanyRow(company.id(), company.code(), company.name(), company.legalName(), status,
                    company.createdAt());
        });
    }

    /**
     * Makes a customer, invited or active, a member of a company (S1A-AC-50). Adding again changes
     * nothing and is not audited again. A membership request that waits for this account in this company
     * is closed as approved (S2-AC-38).
     */
    public MemberRow addCustomer(Viewer viewer, AuditLog.Actor admin, String code, long accountId) {
        return transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            AccountRow customer = accounts.findById(accountId).orElseThrow(ApiException::notFound);
            if (customer.role() != Role.CUSTOMER) {
                throw ApiException.invalid("accountId", "UNKNOWN_VALUE");
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw notActive();
            }
            admit(admin, company, customer.id());
            return member(company, customer, companies.lockManager(company.id(), customer.id()).orElse(false));
        });
    }

    /**
     * The membership, in the caller's transaction, for a company and a customer already checked. An open
     * membership request for the account is locked first, before any insert, and closed as approved; the
     * membership takes its row ID, so the manager's row stays the same row (S2-AC-34). Leaves
     * {@code MEMBERSHIP_REQUEST_APPROVED} for the request and {@code CUSTOMER_ADDED} for the membership.
     */
    void admit(AuditLog.Actor admin, CompanyRow company, long accountId) {
        Instant now = now();
        RequestRow request = requests.lockPending(company.id(), accountId).orElse(null);
        boolean added = request == null ? companies.insertMember(company.id(), accountId, now)
                : companies.insertMember(company.id(), accountId, request.rowId(), now);
        if (request != null) {
            requests.updateClosed(request.id(), State.APPROVED, admin.accountId(), now);
            audit.record(admin, AuditAction.MEMBERSHIP_REQUEST_APPROVED, target(company.code(), accountId),
                    Map.of("email", request.email()));
        }
        if (added) {
            audit.record(admin, AuditAction.CUSTOMER_ADDED, target(company.code(), accountId), Map.of("member", true));
        }
    }

    /**
     * Marks a customer of a company, invited or active, as its manager (S2-AC-01, S2-AC-05). An account
     * that is no customer of the company is 404 (S2-AC-02). Marking again changes nothing and is not
     * audited again. An {@code ARCHIVED} company takes no new manager (S2-AC-06).
     */
    public MemberRow markManager(Viewer viewer, AuditLog.Actor admin, String code, long accountId) {
        return changeManager(viewer, admin, code, accountId, true);
    }

    /** Takes the manager mark away, in a company of any status; the customer stays a member (S2-AC-01). */
    public MemberRow unmarkManager(Viewer viewer, AuditLog.Actor admin, String code, long accountId) {
        return changeManager(viewer, admin, code, accountId, false);
    }

    private MemberRow changeManager(Viewer viewer, AuditLog.Actor admin, String code, long accountId,
            boolean manager) {
        return transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            // The membership row is locked before the mark is read: a manager's write that holds it shared
            // ends first, and the next one reads this change (S2-AC-11).
            boolean marked = companies.lockManager(company.id(), accountId).orElseThrow(ApiException::notFound);
            AccountRow customer = accounts.findById(accountId).orElseThrow(ApiException::notFound);
            if (manager && company.status() == CompanyStatus.ARCHIVED) {
                throw notActive();
            }
            if (marked != manager) {
                companies.updateManager(company.id(), accountId, manager);
                audit.record(admin, manager ? AuditAction.MANAGER_MARKED : AuditAction.MANAGER_UNMARKED,
                        target(company.code(), accountId), Map.of("manager", manager));
            }
            return member(company, customer, manager);
        });
    }

    private static MemberRow member(CompanyRow company, AccountRow account, boolean manager) {
        return new MemberRow(company.id(), account.id(), account.name(), account.email(), account.status(),
                account.photoUpdatedAt(), manager);
    }

    /**
     * Takes a customer out of a company of any status (S1A-AC-52). What the customer may still see is
     * read from the membership on every request ({@code IssueScope}). Removing again changes nothing and
     * is not audited again.
     */
    public void removeCustomer(Viewer viewer, AuditLog.Actor admin, String code, long accountId) {
        transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            AccountRow customer = accounts.findById(accountId).orElseThrow(ApiException::notFound);
            if (companies.deleteMember(company.id(), customer.id())) {
                audit.record(admin, AuditAction.CUSTOMER_REMOVED, target(company.code(), customer.id()),
                        Map.of("member", false));
            }
        });
    }

    /**
     * Takes an agent off a company of any status (S1A-AC-55). Removing again changes nothing and is not
     * audited again.
     *
     * <p>With {@code clearAssignments}, the same transaction then clears the assignee of the open issues
     * of this company the agent is the assignee of, after every check (S3-AC-69, S3-AC-71); the issues
     * of other companies and closed issues keep theirs. The audit entry holds how many as
     * {@code clearedIssues}, 0 too; without {@code clearAssignments} it has no such key (S3-AC-72). An
     * agent who was no longer assigned but still had such issues gets them cleared, and an entry with
     * that number alone.
     *
     * @return how many issues were cleared; 0 without {@code clearAssignments}
     */
    public int unassignAgent(Viewer viewer, AuditLog.Actor admin, String code, long accountId,
            boolean clearAssignments) {
        return transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            AccountRow agent = accounts.findById(accountId).orElseThrow(ApiException::notFound);
            boolean removed = companies.deleteAgent(company.id(), agent.id());
            Map<String, Object> changes = new LinkedHashMap<>();
            if (removed) {
                changes.put("assigned", false);
            }
            int cleared = 0;
            if (clearAssignments) {
                cleared = assignments.clear(agent.id(), company.id(), admin.accountId());
                if (removed || cleared > 0) {
                    changes.put("clearedIssues", cleared);
                }
            }
            if (!changes.isEmpty()) {
                audit.record(admin, AuditAction.AGENT_UNASSIGNED, target(company.code(), agent.id()), changes);
            }
            return cleared;
        });
    }

    /**
     * Assigns an agent, invited or active, to a company (S0-AC-31). Assigning again changes nothing and
     * is not audited again.
     */
    public MemberRow assignAgent(Viewer viewer, AuditLog.Actor admin, String code, long accountId) {
        return transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            AccountRow agent = accounts.findById(accountId).orElseThrow(ApiException::notFound);
            if (agent.role() != Role.AGENT) {
                throw ApiException.invalid("accountId", "UNKNOWN_VALUE");
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw notActive();
            }
            if (companies.insertAgent(company.id(), agent.id(), now())) {
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("assigned", true);
                audit.record(admin, AuditAction.AGENT_ASSIGNED, target(company.code(), agent.id()), changes);
            }
            return member(company, agent, false);
        });
    }

    private static Map<String, Object> target(String code) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "COMPANY");
        target.put("code", code);
        return target;
    }

    /** The target of a membership or an assignment: the company and the account, by ID. */
    static Map<String, Object> target(String code, long accountId) {
        Map<String, Object> target = target(code);
        target.put("accountId", accountId);
        return target;
    }

    /** A changed value of an audit row: what it was and what it is (S1A-AC-63). */
    private static Map<String, Object> change(String from, String to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private static ApiException archived() {
        return new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_ARCHIVED", "The company is archived.");
    }

    static ApiException notActive() {
        return new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
