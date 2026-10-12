package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * A public comment. Customers never see internal comments (CMT-2, S0-AC-53), who deleted a comment, or
 * whom it mentions (S4-AC-15, S4-AC-25). A deleted comment stays in the list as a trace (COM-7).
 */
public record Comment(
        long id,
        Person author,
        @Schema(description = "Markdown, as written (COM-6). An empty string once the comment is deleted (COM-7).")
                String content,
        OffsetDateTime createdAt,
        @Schema(description = "When the author last changed the content; null when it never changed (S4-AC-07).",
                nullable = true) OffsetDateTime editedAt,
        boolean deleted,
        @Schema(nullable = true) OffsetDateTime deletedAt) {}
