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
        OffsetDateTime createdAt) {}
