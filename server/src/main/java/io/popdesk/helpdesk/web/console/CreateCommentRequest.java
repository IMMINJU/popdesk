package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CreateCommentRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Markdown, 1 to 4,000 characters.") String content,
        @Schema(description = "An internal comment, hidden from customers. Public when left out.") Boolean internal,
        @Schema(description = "Account IDs to mention, up to 10, each one of `listMentionCandidates` for this kind "
                + "of comment (MNT-1). An ID given twice counts once. Left out is the same as an empty list.")
                List<Long> mentions,
        @Schema(implementation = UUID[].class, description = ConsoleImageController.IMAGE_IDS) JsonNode imageIds) {}
