package io.popdesk.helpdesk.web.console;

import io.popdesk.ApiException;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.issue.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** The periods the SLA columns can cover, in days (S8B-AC-21). */
    private static final Set<Integer> DAYS = Set.of(7, 30, 90);

    private final IssueService issues;

    ConsoleStatsController(IssueService issues) {
        this.issues = issues;
    }

    @Operation(operationId = "getCompanyStats", summary = "Issue counts per company",
            description = "One item for each company the caller can see: every company for admins, assigned ones "
                    + "for agents (S7B-AC-03). A company the caller is not assigned to has no item, and its issues "
                    + "are in no count. Companies of every status are listed, archived ones too, and a company "
                    + "without issues has an item with zeros (S7B-AC-01). Issues in the trash and purged ones are "
                    + "not counted; private issues are (S7B-AC-02). Ordered by `open`, the most first, then by "
                    + "`companyCode` (S7B-AC-04). Not paged: every item at once. While the SLA policy is on, "
                    + "each item has the SLA goals of its issues over the last `days` (S8B-AC-22).")
    @ApiResponse(responseCode = "200", description = "The counts as they are now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `days` is not 7, 30, or 90 "
            + "(`UNKNOWN_VALUE`), or not a number (`INVALID_FORMAT`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @GetMapping
    CompanyStats getCompanyStats(
            @Parameter(description = "The SLA columns count goals that stopped within the last `days` × 24 hours: "
                    + "7, 30, or 90 (S8B-AC-21, S8B-AC-23). The issue counts are as they are now whatever it is.")
                    @RequestParam(defaultValue = "30") int days) {
        if (!DAYS.contains(days)) {
            throw ApiException.invalid("days", "UNKNOWN_VALUE");
        }
        return new CompanyStats(issues.companyStats(CurrentAccount.get().viewer()).stream()
                .map(c -> new CompanyStat(c.code(), c.name(), c.status(), c.total(), c.open(), c.inProgress(),
                        c.resolved(), c.closed(), null))
                .toList());
    }
}
