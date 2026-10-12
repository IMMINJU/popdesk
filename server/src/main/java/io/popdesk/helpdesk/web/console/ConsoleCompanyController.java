package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.popdesk.PatchText;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyRepository.MemberRow;
import io.popdesk.helpdesk.company.CompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Companies")
@RestController
@RequestMapping("/api/console/companies")
class ConsoleCompanyController {

    private final CompanyService companies;

    ConsoleCompanyController(CompanyService companies) {
        this.companies = companies;
    }

    static Company company(CompanyRow row, int pendingMembershipRequests) {
        return new Company(row.code(), row.name(), row.legalName(), row.status(),
                row.createdAt().atOffset(ZoneOffset.UTC), pendingMembershipRequests);
    }

    /** The company with the membership requests that wait for the caller, an admin; 0 for an agent. */
    private Company company(CompanyRow row) {
        return company(row, companies.pendingRequests(CurrentAccount.get().viewer(), List.of(row))
                .getOrDefault(row.code(), 0));
    }

    static CompanyMember member(MemberRow row) {
        return new CompanyMember(row.accountId(), row.name(), row.email(), row.status(),
                row.photoUpdatedAt() == null ? null : row.photoUpdatedAt().atOffset(ZoneOffset.UTC), row.manager());
    }

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only (S0-AC-30).";
    static final String NOT_FOUND = "`NOT_FOUND`: no such company, or the caller cannot see it.";
    static final String NOT_A_MEMBER = "`NOT_FOUND`: no such company, or the account is not a customer who belongs "
            + "to it (S2-AC-02).";
    static final String CLEAR_ASSIGNMENTS = "`true`: in the same request, also clear the assignee of every issue counted by "
            + "`countCompanyOpenAssignments` (S3-AC-69). Each of them gets a history entry by the caller, and no webhook message "
            + "is sent (S3-AC-70). Left out or `false`, assignees stay.";
    static final String PAGE_400 = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).";

    @Operation(operationId = "listCompanies", summary = "Companies the caller can see",
            description = "All companies for admins, assigned ones for agents (S0-AC-32), in code order.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = PAGE_400)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    Paged<Company> listCompanies(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        Paged<CompanyRow> rows = companies.list(CurrentAccount.get().viewer(), page, size);
        Map<String, Integer> pending = companies.pendingRequests(CurrentAccount.get().viewer(), rows.items());
        return rows.map(row -> company(row, pending.getOrDefault(row.code(), 0)));
    }

    @Operation(operationId = "createCompany", summary = "Create a company",
            description = "Creates an `ACTIVE` company (S0-AC-29).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `name` or `legalName` empty or over 100, "
            + "`code` not 2 to 10 capital letters and digits (`INVALID_FORMAT`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "409", description = "`COMPANY_CODE_TAKEN`: another company has this code.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Company createCompany(@RequestBody CreateCompanyRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        return company(companies.create(admin.actor(), request.code(), request.name(), request.legalName()));
    }

    @Operation(operationId = "getCompany", summary = "One company")
    @ApiResponse(responseCode = "200", description = "The company.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @GetMapping("/{code}")
    Company getCompany(@PathVariable String code) {
        return company(companies.get(CurrentAccount.get().viewer(), code));
    }

    @Operation(operationId = "listCompanyCustomers", summary = "Customers of a company",
            description = "Customers who belong to the company, invited ones included, in name order (C-9).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = PAGE_400)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @GetMapping("/{code}/customers")
    Paged<CompanyMember> listCompanyCustomers(
            @PathVariable String code,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return members(code, Role.CUSTOMER, page, size);
    }

    @Operation(operationId = "listCompanyAgents", summary = "Agents assigned to a company",
            description = "In name order (C-9).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = PAGE_400)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @GetMapping("/{code}/agents")
    Paged<CompanyMember> listCompanyAgents(
            @PathVariable String code,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return members(code, Role.AGENT, page, size);
    }

    @Operation(operationId = "assignAgent", summary = "Assign an agent to a company",
            description = "The agent, invited ones included, sees the company's issues from the next request. "
                    + "Assigning again is 200 too (S0-AC-31).")
    @ApiResponse(responseCode = "200", description = "Assigned.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: the account is not an agent (field "
            + "`accountId` `UNKNOWN_VALUE`). `COMPANY_NOT_ACTIVE`: the company is `ARCHIVED`.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company or account.")
    @PutMapping("/{code}/agents/{accountId}")
    CompanyMember assignAgent(@PathVariable String code, @PathVariable long accountId) {
        CurrentAccount admin = CurrentAccount.get();
        return member(companies.assignAgent(admin.viewer(), admin.actor(), code, accountId));
    }

    @Operation(operationId = "updateCompany", summary = "Change a company's name or legal name",
            description = "A field left out keeps its value. The code never changes (S1A-AC-43).")
    @ApiResponse(responseCode = "200", description = "Saved.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `name` or `legalName` empty or over 100, or "
            + "`null` (`REQUIRED`); `code` sent (`NOT_ALLOWED`). `COMPANY_ARCHIVED`: the company is `ARCHIVED` "
            + "(S1A-AC-48).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PatchMapping("/{code}")
    Company updateCompany(@PathVariable String code, @RequestBody UpdateCompanyRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        return company(companies.update(admin.viewer(), admin.actor(), code, PatchText.of("name", request.name()),
                PatchText.of("legalName", request.legalName()), request.code() != null));
    }

    @Operation(operationId = "changeCompanyStatus", summary = "Change a company's status",
            description = "`ACTIVE` to `SUSPENDED` and back, or either to `ARCHIVED` (S1A-AC-44). The status the "
                    + "company already has is 200 too, for `ACTIVE` and `SUSPENDED`. Nothing new can "
                    + "be filed in a company that is not `ACTIVE`; what is there stays readable (S1A-AC-46, "
                    + "S1A-AC-47).")
    @ApiResponse(responseCode = "200", description = "Changed.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `status` missing (`REQUIRED`) or unknown "
            + "(`UNKNOWN_VALUE`). `COMPANY_ARCHIVED`: the company is `ARCHIVED`, which is final (S1A-AC-45).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @PutMapping("/{code}/status")
    Company changeCompanyStatus(@PathVariable String code, @RequestBody ChangeCompanyStatusRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        return company(companies.changeStatus(admin.viewer(), admin.actor(), code, request.status()));
    }

    @Operation(operationId = "addCompanyCustomer", summary = "Make a customer a member of a company",
            description = "The customer, invited ones included, sees the company and its public issues from the "
                    + "next request. Adding again is 200 too (S1A-AC-50).")
    @ApiResponse(responseCode = "200", description = "A member.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: the account is not a customer (field "
            + "`accountId` `UNKNOWN_VALUE`) (S1A-AC-51). `COMPANY_NOT_ACTIVE`: the company is `ARCHIVED` "
            + "(S1A-AC-48).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company or account.")
    @PutMapping("/{code}/customers/{accountId}")
    CompanyMember addCompanyCustomer(@PathVariable String code, @PathVariable long accountId) {
        CurrentAccount admin = CurrentAccount.get();
        return member(companies.addCustomer(admin.viewer(), admin.actor(), code, accountId));
    }

    @Operation(operationId = "removeCompanyCustomer", summary = "Take a customer out of a company",
            description = "From the next request the customer no longer sees the company or colleagues' issues; "
                    + "issues the customer filed stay readable to them but take no new comments or files from "
                    + "them (S1A-AC-52 to S1A-AC-54). Works for a company of any status, and removing again is 200 "
                    + "too.")
    @ApiResponse(responseCode = "200", description = "Not a member.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company or account.")
    @DeleteMapping("/{code}/customers/{accountId}")
    void removeCompanyCustomer(@PathVariable String code, @PathVariable long accountId) {
        CurrentAccount admin = CurrentAccount.get();
        companies.removeCustomer(admin.viewer(), admin.actor(), code, accountId);
    }

    @Operation(operationId = "markCompanyManager", summary = "Make a customer a manager of a company",
            description = "The customer, invited ones included, manages the members of this company from the next "
                    + "request; in their other companies nothing changes (S2-AC-01, S2-AC-03, S2-AC-05). Marking "
                    + "again is 200 too.")
    @ApiResponse(responseCode = "200", description = "A manager.")
    @ApiResponse(responseCode = "400", description = "`COMPANY_NOT_ACTIVE`: the company is `ARCHIVED` (S2-AC-06).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_A_MEMBER)
    @PutMapping("/{code}/customers/{accountId}/manager")
    CompanyMember markCompanyManager(@PathVariable String code, @PathVariable long accountId) {
        CurrentAccount admin = CurrentAccount.get();
        return member(companies.markManager(admin.viewer(), admin.actor(), code, accountId));
    }

    @Operation(operationId = "unmarkCompanyManager", summary = "Make a customer no longer a manager of a company",
            description = "The customer stays a member. Applies from the next request (S2-AC-01). Works for a "
                    + "company of any status, and unmarking again is 200 too.")
    @ApiResponse(responseCode = "200", description = "Not a manager.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_A_MEMBER)
    @DeleteMapping("/{code}/customers/{accountId}/manager")
    CompanyMember unmarkCompanyManager(@PathVariable String code, @PathVariable long accountId) {
        CurrentAccount admin = CurrentAccount.get();
        return member(companies.unmarkManager(admin.viewer(), admin.actor(), code, accountId));
    }

    @Operation(operationId = "listAssigneeCandidates", summary = "Who can be assignee of the company's issues",
            description = "Every `ACTIVE` admin and every `ACTIVE` agent assigned to the company, in name order "
                    + "(S3-AC-27). All of them at once, without pages: the list fills a picker.")
    @ApiResponse(responseCode = "200", description = "The candidates.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @GetMapping("/{code}/assignees")
    AssigneeCandidates listAssigneeCandidates(@PathVariable String code) {
        return new AssigneeCandidates(companies.assigneeCandidates(CurrentAccount.get().viewer(), code).stream()
                .map(row -> ConsoleIssueController.person(row.accountId(), row.name(), row.role(),
                        row.photoUpdatedAt()))
                .toList());
    }

    @Operation(operationId = "listCompanyReporters", summary = "Customers an issue can be filed on behalf of",
            description = "The `ACTIVE` customers who belong to the company, in name order: who can be "
                    + "`reporterId` of `createIssue` (S3-AC-40). For agents assigned to the company and for "
                    + "admins. Customers who have not accepted their invitation and suspended ones are left out.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = PAGE_400 + " `q` empty or over 100 characters (`TOO_SHORT`, "
            + "`TOO_LONG`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 60 lists with `q` in 60 seconds, "
            + "counted together with the other lists that search (COM-8).")
    @GetMapping("/{code}/reporters")
    Paged<CompanyReporter> listCompanyReporters(
            @PathVariable String code,
            @Parameter(description = "Only customers whose name or email holds this text, 1 to 100 characters. "
                    + "Case is ignored; `%` and `_` are ordinary characters (COM-5).")
                    @RequestParam(required = false) @Nullable String q,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "countCompanyOpenAssignments",
            summary = "What an agent is still assigned to in a company",
            description = "Issues of this company that are not `CLOSED` and have the agent as assignee: what "
                    + "`unassignAgent` clears with `clearAssignments` (S3-AC-68).")
    @ApiResponse(responseCode = "200", description = "The counts.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company or account.")
    @GetMapping("/{code}/agents/{accountId}/open-assignments")
    OpenAssignments countCompanyOpenAssignments(@PathVariable String code, @PathVariable long accountId) {
        return new OpenAssignments(companies.openAssignments(CurrentAccount.get().viewer(), code, accountId));
    }

    @Operation(operationId = "unassignAgent", summary = "Take an agent off a company",
            description = "From the next request the agent no longer sees the company's issues, comments, or files "
                    + "(S1A-AC-55). Works for a company of any status, and removing again is 200 too. Issues "
                    + "keep the agent as assignee, marked `assigneeUnavailable`, unless `clearAssignments` is "
                    + "sent (S3-AC-31).")
    @ApiResponse(responseCode = "200", description = "Not assigned. With `clearAssignments=true` the body says "
            + "what was cleared; without it there is no body.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company or account.")
    @DeleteMapping("/{code}/agents/{accountId}")
    ResponseEntity<ClearedAssignments> unassignAgent(@PathVariable String code, @PathVariable long accountId,
            @Parameter(description = CLEAR_ASSIGNMENTS) @RequestParam(defaultValue = "false")
                    boolean clearAssignments) {
        CurrentAccount admin = CurrentAccount.get();
        int cleared = companies.unassignAgent(admin.viewer(), admin.actor(), code, accountId, clearAssignments);
        return clearAssignments ? ResponseEntity.ok(new ClearedAssignments(cleared)) : ResponseEntity.ok().build();
    }

    private Paged<CompanyMember> members(String code, Role role, int page, int size) {
        Paged<MemberRow> rows = companies.members(CurrentAccount.get().viewer(), code, role, page, size);
        return new Paged<>(rows.items().stream().map(ConsoleCompanyController::member).toList(), rows.page(),
                rows.size(), rows.totalElements(), rows.totalPages());
    }
}
