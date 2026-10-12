package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). The field is read as JSON to tell {@code null} from a
 * field left out.
 */
public record UpdateCommentRequest(
        @Schema(type = "string", description = "Markdown, 1 to 4,000 characters. `null` is refused.")
                JsonNode content) {}
