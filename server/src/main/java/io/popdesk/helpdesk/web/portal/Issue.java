package io.popdesk.helpdesk.web.portal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An issue as a customer sees it. No internal fields (S0-AC-49). */
public record Issue(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        String companyCode,
        String companyName,
        String title,
        @Schema(description = "Markdown, as written (COM-6).") String content,
        IssueStatus status,
        Priority priority,
        @JsonProperty("private")
                @Schema(description = "Visible only to the reporter and staff.") boolean privateIssue,
        Person reporter,
        @Schema(description = "The support team filed the issue for the reporter (S3-AC-36).") boolean filedBySupport,
        @Schema(description = "When the issue became `RESOLVED`; null when it is not resolved (S3-AC-59).",
                nullable = true) OffsetDateTime resolvedAt,
        @Schema(description = "When the issue became `CLOSED`; null while it is open. It can be reopened for 14 "
                + "days from then.", nullable = true) OffsetDateTime closedAt,
        @Schema(description = "`reopenIssue` can work now: the issue is `RESOLVED`, or `CLOSED` for less than 14 "
                + "days (STATUS-4). It says nothing about who the caller is.") boolean reopenable,
        @Schema(description = "Goes up by 1 on every change to the issue (S0-AC-45).") long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
