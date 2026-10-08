package io.popdesk.helpdesk.web.portal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.popdesk.helpdesk.Priority;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateIssueRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Code of an active company the caller belongs to.") String companyCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "1 to 200 characters after trimming.") String title,
        @Schema(description = "Markdown, up to 10,000 characters. Empty when left out.") String content,
        @Schema(description = "`NORMAL` when left out.") Priority priority,
        @JsonProperty("private")
                @Schema(description = "Public when left out.") Boolean privateIssue) {}
