package io.popdesk.helpdesk.web.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** An issue as staff see it. */
public record Issue(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        String companyCode,
        String companyName,
        String title,
        @Schema(description = "Markdown, as written (COM-6).") String content,
        IssueStatus status,
        Priority priority,
        @JsonProperty("private") boolean privateIssue,
        Person reporter,
        @Schema(description = "The staff member who filed the issue for the reporter; null when the reporter "
                + "filed it (S3-AC-36).", nullable = true) Person createdBy,
        IssueChannel channel,
        @Schema(description = "Null when nobody is assigned (S3-AC-28).", nullable = true) Person assignee,
        @Schema(description = "The assignee can no longer see the issue: the account is not `ACTIVE`, or it is "
                + "an agent who is not assigned to the company (ISSUE-11). `false` without an assignee.")
                boolean assigneeUnavailable,
        @Schema(description = "The statuses `changeIssueStatus` takes now (STATUS-1). Empty for a `CLOSED` "
                + "issue.") List<IssueStatus> nextStatuses,
        @Schema(description = "`reopenIssue` works now: the issue is `RESOLVED`, or `CLOSED` for less than 14 "
                + "days (STATUS-4).") boolean reopenable,
        @Schema(description = "When staff first commented publicly (S0-AC-46).", nullable = true)
                OffsetDateTime firstResponseAt,
        @Schema(description = "When the issue became `RESOLVED`; null when it is not resolved (S3-AC-59).",
                nullable = true) OffsetDateTime resolvedAt,
        @Schema(description = "When the issue became `CLOSED`; null while it is open.", nullable = true)
                OffsetDateTime closedAt,
        @Schema(description = "Goes up by 1 on every change to the issue (S0-AC-45).") long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
