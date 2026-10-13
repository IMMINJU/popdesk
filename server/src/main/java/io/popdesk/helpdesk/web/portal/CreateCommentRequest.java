package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CreateCommentRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Markdown, 1 to 4,000 characters.") String content,
        @Schema(implementation = UUID[].class, description = PortalImageController.IMAGE_IDS) JsonNode imageIds) {}
