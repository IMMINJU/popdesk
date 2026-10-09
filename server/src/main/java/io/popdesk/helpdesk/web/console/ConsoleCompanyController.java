package io.popdesk.helpdesk.web.console;

import io.popdesk.Paged;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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

    static Company company(CompanyRow row) {
        return new Company(row.code(), row.name(), row.legalName(), row.status(),
                row.createdAt().atOffset(ZoneOffset.UTC));
    }

    static CompanyMember member(MemberRow row) {
        return new CompanyMember(row.accountId(), row.name(), row.email(), row.status());
    }

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only (S0-AC-30).";
    static final String NOT_FOUND = "`NOT_FOUND`: no such company, or the caller cannot see it.";
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
        return new Paged<>(rows.items().stream().map(ConsoleCompanyController::company).toList(), rows.page(),
                rows.size(), rows.totalElements(), rows.totalPages());
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

    private Paged<CompanyMember> members(String code, Role role, int page, int size) {
        Paged<MemberRow> rows = companies.members(CurrentAccount.get().viewer(), code, role, page, size);
        return new Paged<>(rows.items().stream().map(ConsoleCompanyController::member).toList(), rows.page(),
                rows.size(), rows.totalElements(), rows.totalPages());
    }
}
