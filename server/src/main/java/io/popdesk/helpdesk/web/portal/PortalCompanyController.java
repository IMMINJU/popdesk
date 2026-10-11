package io.popdesk.helpdesk.web.portal;

import io.popdesk.Paged;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.company.CompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Companies")
@RestController
class PortalCompanyController {

    private final CompanyService companies;

    PortalCompanyController(CompanyService companies) {
        this.companies = companies;
    }

    @Operation(operationId = "listMyCompanies", summary = "Companies the caller belongs to",
            description = "Only the companies the caller belongs to (S0-AC-32), in code order.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping("/api/portal/companies")
    Paged<Company> listMyCompanies(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        Paged<CompanyRow> rows = companies.list(CurrentAccount.get().viewer(), page, size);
        return new Paged<>(rows.items().stream().map(c -> new Company(c.code(), c.name(), c.status(), false)).toList(),
                rows.page(), rows.size(), rows.totalElements(), rows.totalPages());
    }
}
