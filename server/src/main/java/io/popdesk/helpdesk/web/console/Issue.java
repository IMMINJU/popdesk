package io.popdesk.helpdesk.web.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

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
        IssueChannel channel,
        @Schema(description = "When staff first commented publicly (S0-AC-46).", nullable = true)
                OffsetDateTime firstResponseAt,
        @Schema(description = "Goes up by 1 on every change to the issue (S0-AC-45).") long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
