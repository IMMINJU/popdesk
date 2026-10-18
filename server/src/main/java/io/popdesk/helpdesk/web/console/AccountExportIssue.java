package io.popdesk.helpdesk.web.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.IssueStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An issue in an {@link AccountExport}: no assignee, no one who filed it, no company name (S10A-AC-05). */
public record AccountExportIssue(
        String key,
        String companyCode,
        String title,
        @Schema(description = "Markdown, as written (COM-6).") String content,
        IssueStatus status,
        @JsonProperty("private") boolean privateIssue,
        OffsetDateTime createdAt,
        @Schema(description = "Null while it is open.", nullable = true) OffsetDateTime closedAt,
        @Schema(description = "When it went into the trash; null when it is not there.", nullable = true)
                OffsetDateTime deletedAt) {}
