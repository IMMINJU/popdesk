package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.CompanyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Issue counts of one company (S7B-AC-01). Each count is of the company's issues, public and private,
 * that are not in the trash and not purged (S7B-AC-02).
 */
public record CompanyStat(
        String companyCode,
        String companyName,
        @Schema(description = "The company's status now; archived companies are listed too.")
                CompanyStatus companyStatus,
        @Schema(description = "Every issue, in any status; the sum of the four counts below.") long total,
        @Schema(description = "Issues that are `RECEIVED` or `CONFIRMED`.") long open,
        @Schema(description = "Issues that are `IN_PROGRESS`.") long inProgress,
        @Schema(description = "Issues that are `RESOLVED`.") long resolved,
        @Schema(description = "Issues that are `CLOSED`.") long closed) {}
