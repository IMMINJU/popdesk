package io.popdesk.helpdesk.company;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PageRequest;
import io.popdesk.Paged;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.account.AccountRepository;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyRepository.MemberRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer companies (PROJ-1), agent assignments (PROJ-4), and the company lists (PROJ-5). The order
 * of checks is the one of conventions: the caller's role is checked by the security filter, then the
 * target is looked up in the caller's scope (404), then the input (400), then the state (409).
 */
@Service
public class CompanyService {

    /** 2 to 10 capital letters and digits (D-12). Small letters are not turned into capitals. */
    static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,10}");
    static final int NAME_MAX = 100;

    private final CompanyRepository companies;
    private final AccountRepository accounts;
    private final AuditLog audit;
    private final Transactions transactions;
    private final Clock clock;

    CompanyService(CompanyRepository companies, AccountRepository accounts, AuditLog audit, Transactions transactions,
            Clock clock) {
        this.companies = companies;
        this.accounts = accounts;
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
            audit.record(admin, "COMPANY_CREATED", target, changes);
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

    /** Customers or agents of a company: the company first (404), then the page (400). */
    @Transactional(readOnly = true)
    public Paged<MemberRow> members(Viewer viewer, String code, Role role, int page, int size) {
        CompanyRow company = get(viewer, code);
        PageRequest request = PageRequest.of(page, size);
        return request.of(companies.listMembers(viewer, company.id(), role, request.offset(), request.size()),
                companies.countMembers(viewer, company.id(), role));
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
            if (company.status() != CompanyStatus.ACTIVE) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
            }
            if (companies.insertAgent(company.id(), agent.id(), now())) {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("type", "COMPANY");
                target.put("code", company.code());
                target.put("accountId", agent.id());
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("assigned", true);
                audit.record(admin, "AGENT_ASSIGNED", target, changes);
            }
            return new MemberRow(company.id(), agent.id(), agent.name(), agent.email(), agent.status());
        });
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
