package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A file on an issue or a comment. A deleted attachment stays in the list as a trace (COM-7). */
public record Attachment(
        @Schema(description = "Hard to guess, but not a permission check (ATT-7).") UUID id,
        String fileName,
        @Schema(description = "Bytes.") long size,
        String contentType,
        @Schema(description = "The comment it belongs to; null for a file on the issue itself.", nullable = true)
                Long commentId,
        @Schema(description = "On an internal comment.") boolean internal,
        Person uploadedBy,
        OffsetDateTime createdAt,
        boolean deleted,
        @Schema(nullable = true) OffsetDateTime deletedAt,
        @Schema(nullable = true) Person deletedBy) {}
