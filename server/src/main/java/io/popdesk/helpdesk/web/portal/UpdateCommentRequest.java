package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). The fields are read as JSON to tell {@code null} from a
 * field left out.
 */
public record UpdateCommentRequest(
        @Schema(type = "string", description = "Markdown, 1 to 4,000 characters. `null` is refused.")
                JsonNode content,
        @Schema(implementation = UUID[].class, description = PortalImageController.IMAGE_IDS) JsonNode imageIds) {}
