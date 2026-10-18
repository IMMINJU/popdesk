package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The body of asking for a company export (S10B-AC-01). */
public record CreateCompanyExportRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Also put internal comments and "
                + "tasks in the ZIP, with the attachments of those comments (S10B-AC-13, S10B-AC-14).")
                Boolean includeInternal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Also put private issues in the ZIP: "
                + "issues other customers of the same company cannot see either (S10B-AC-16).")
                Boolean includePrivate) {}
