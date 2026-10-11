package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The result of checking the audit log's hash chain from its start (S1B-AC-14). */
public record AuditVerification(
        @Schema(description = "Every entry and the end of the chain hold.") boolean valid,
        @Schema(description = "Entries whose hash was computed again and matched.") long checkedCount,
        @Schema(description = "Null when `valid`.", nullable = true) AuditBreakReason reason,
        @Schema(description = "The first entry that does not hold. Null when `valid`, and for `HEAD_MISMATCH`, "
                + "where no entry that is left is wrong.", nullable = true) AuditBrokenEntry brokenEntry) {}
