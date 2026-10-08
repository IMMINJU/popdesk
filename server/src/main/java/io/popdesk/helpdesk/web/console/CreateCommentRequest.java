package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateCommentRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Markdown, 1 to 4,000 characters.") String content,
        @Schema(description = "An internal comment, hidden from customers. Public when left out.") Boolean internal) {}
