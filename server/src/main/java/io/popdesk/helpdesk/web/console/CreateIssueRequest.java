package io.popdesk.helpdesk.web.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** An issue staff file on behalf of a customer (ISSUE-9, ADR D-34). */
public record CreateIssueRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Code of an active company the caller can see.") String companyCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Account ID of an `ACTIVE` customer who belongs to that company. The customer is "
                        + "the reporter of the issue.") Long reporterId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PHONE", "OTHER"},
                description = "How the request came in.") String channel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "1 to 200 characters after trimming.") String title,
        @Schema(description = "Markdown, up to 10,000 characters. Empty when left out.") String content,
        @Schema(allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"},
                description = "`NORMAL` when left out.") String priority,
        @JsonProperty("private")
                @Schema(description = "Public when left out.") Boolean privateIssue) {}
