package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A public or internal comment. */
public record Comment(
        long id,
        Person author,
        @Schema(description = "Internal comments do not exist for customers (CMT-2).") boolean internal,
        @Schema(description = "Markdown, as written (COM-6).") String content,
        OffsetDateTime createdAt) {}
