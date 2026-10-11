package io.popdesk.helpdesk.web.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A row of the staff issue list. */
public record IssueSummary(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        String title,
        String companyCode,
        String companyName,
        IssueStatus status,
        Priority priority,
        @JsonProperty("private") boolean privateIssue,
        Person reporter,
        @Schema(description = "Staff filed the issue for the reporter (ISSUE-9).") boolean filedOnBehalf,
        @Schema(description = "Null when nobody is assigned (S3-AC-28).", nullable = true) Person assignee,
        @Schema(description = "The assignee can no longer see the issue (ISSUE-11). `false` without an "
                + "assignee.") boolean assigneeUnavailable,
        OffsetDateTime createdAt) {}
