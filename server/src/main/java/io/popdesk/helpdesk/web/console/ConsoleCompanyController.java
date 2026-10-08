package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
        throw new NotImplementedException();
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
        throw new NotImplementedException();
    }

    @Operation(operationId = "getCompany", summary = "One company")
    @ApiResponse(responseCode = "200", description = "The company.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @GetMapping("/{code}")
    Company getCompany(@PathVariable String code) {
        throw new NotImplementedException();
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
        throw new NotImplementedException();
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
        throw new NotImplementedException();
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
        throw new NotImplementedException();
    }

    @Operation(operationId = "unassignAgent", summary = "Unassign an agent from a company",
            description = "The agent loses access to the company's issues at once; assignees stay (PROJ-4). "
                    + "Unassigning an agent who is not assigned is 204 too.")
    @ApiResponse(responseCode = "204", description = "Unassigned.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company or account.")
    @DeleteMapping("/{code}/agents/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unassignAgent(@PathVariable String code, @PathVariable long accountId) {
        throw new NotImplementedException();
    }
}
