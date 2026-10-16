package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue counts per company (STATS-1 to STATS-3), for staff. Counted from the issue table on every
 * request; there is no table of totals. Customers never reach this path: a portal host answers 404 and
 * a console host 401, as for every console path (S7B-AC-03).
 */
@Tag(name = "Statistics")
@RestController
@RequestMapping("/api/console/stats")
class ConsoleStatsController {

    @Operation(operationId = "getCompanyStats", summary = "Issue counts per company",
            description = "One item for each company the caller can see: every company for admins, assigned ones "
                    + "for agents (S7B-AC-03). A company the caller is not assigned to has no item, and its issues "
                    + "are in no count. Companies of every status are listed, archived ones too, and a company "
                    + "without issues has an item with zeros (S7B-AC-01). Issues in the trash and purged ones are "
                    + "not counted; private issues are (S7B-AC-02). Ordered by `open`, the most first, then by "
                    + "`companyCode` (S7B-AC-04). Not paged: every item at once.")
    @ApiResponse(responseCode = "200", description = "The counts as they are now.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    CompanyStats getCompanyStats() {
        throw new NotImplementedException();
    }
}
