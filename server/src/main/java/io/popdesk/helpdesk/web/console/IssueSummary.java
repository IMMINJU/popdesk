package io.popdesk.helpdesk.web.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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
        @Schema(description = "Labels of the issue, in name order whatever the case (S6A-AC-50). Empty without "
                + "labels.") List<LabelRef> labels,
        @Schema(description = "`YYYY-MM-DD`; null without a due date (DUE-1).", nullable = true) LocalDate dueDate,
        @Schema(description = "The due date is before today in the installation time zone (S6A-AC-35). `false` "
                + "without a due date.") boolean overdue,
        @Schema(description = "The SLA goals of the issue; null when it is not under SLA (S8A-AC-30). The worse "
                + "of the two running goals decides the `sla` filter (S8A-AC-31).", nullable = true) IssueSla sla,
        OffsetDateTime createdAt,
        @Schema(description = "Tasks of the issue that are `OPEN` and not deleted, also on a `CLOSED` issue "
                + "(S6B-AC-70). 0 without tasks.") long openTaskCount) {}
