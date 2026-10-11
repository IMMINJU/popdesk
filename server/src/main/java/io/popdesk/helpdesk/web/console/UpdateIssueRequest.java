package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). The fields are read as JSON to tell {@code null} from a
 * field left out. Whether the issue is private and its company never change (S3-AC-23).
 */
public record UpdateIssueRequest(
        @Schema(type = "string", description = "1 to 200 characters after trimming. `null` is refused.")
                JsonNode title,
        @Schema(type = "string", description = "Markdown, up to 10,000 characters. `null` clears it.")
                JsonNode content,
        @Schema(type = "string", allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"},
                description = "`null` is refused.") JsonNode priority,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The `version` of the issue as the caller read it (S3-AC-19).") Long version) {}
