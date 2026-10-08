package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateCommentRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Markdown, 1 to 4,000 characters.") String content) {}
