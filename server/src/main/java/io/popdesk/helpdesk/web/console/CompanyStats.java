package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Issue counts of every company the caller can see, all at once (S7B-AC-04). */
public record CompanyStats(
        @Schema(description = "One item per company: by `open`, the most first, then by `companyCode`. Empty "
                + "when the caller can see no company.") List<CompanyStat> items) {}
