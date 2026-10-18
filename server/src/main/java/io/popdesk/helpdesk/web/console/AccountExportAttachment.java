package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A file in an {@link AccountExport}: what it is, not its content (S10A-AC-04). */
public record AccountExportAttachment(
        String issueKey,
        @Schema(description = "The comment it belongs to; null for a file on the issue itself.", nullable = true)
                Long commentId,
        String fileName,
        @Schema(description = "Bytes.") long size,
        OffsetDateTime createdAt) {}
