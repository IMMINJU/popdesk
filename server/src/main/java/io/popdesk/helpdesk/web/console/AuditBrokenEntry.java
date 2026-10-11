package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** The first audit entry at which the hash chain breaks (S1B-AC-14). */
public record AuditBrokenEntry(
        long id,
        OffsetDateTime occurredAt,
        @Schema(description = "The kind of action as the entry holds it now; it may be a changed value.")
                String action) {}
