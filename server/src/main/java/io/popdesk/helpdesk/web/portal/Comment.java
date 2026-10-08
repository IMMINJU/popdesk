package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** A public comment. Customers never see internal comments (CMT-2, S0-AC-53). */
public record Comment(
        long id,
        Person author,
        @Schema(description = "Markdown, as written (COM-6).") String content,
        OffsetDateTime createdAt) {}
