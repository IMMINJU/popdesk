package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The body of adding a task note and of editing one. */
public record TaskNoteRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Plain text, 1 to 4,000 characters; "
                + "only whitespace is refused (S6B-AC-20). Line breaks are kept.") String content) {}
