package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A comment in an {@link AccountExport}. Mentions are left out: they name other accounts (S10A-AC-05). */
public record AccountExportComment(
        String issueKey,
        @Schema(description = "Markdown, as written (COM-6).") String content,
        boolean internal,
        OffsetDateTime createdAt,
        @Schema(description = "Null when it never changed.", nullable = true) OffsetDateTime editedAt) {}
