package io.popdesk.helpdesk.web.portal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A row of the customer's issue list. */
public record IssueSummary(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        String title,
        String companyCode,
        String companyName,
        IssueStatus status,
        Priority priority,
        @JsonProperty("private")
                @Schema(description = "Visible only to the reporter and staff.") boolean privateIssue,
        Person reporter,
        @Schema(description = "The support team filed the issue for the reporter (S3-AC-36).") boolean filedBySupport,
        @Schema(description = "When the issue became `RESOLVED`; null when it is not resolved (S3-AC-59).",
                nullable = true) OffsetDateTime resolvedAt,
        @Schema(description = "When the issue became `CLOSED`; null while it is open.", nullable = true)
                OffsetDateTime closedAt,
        OffsetDateTime createdAt) {}
