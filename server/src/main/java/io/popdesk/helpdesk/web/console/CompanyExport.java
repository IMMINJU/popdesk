package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One company export: a ZIP of one company's data, written in the background (PII-8, S10B-AC-01). */
public record CompanyExport(
        UUID id,
        @Schema(description = "A `DONE` export whose ZIP is over 24 hours old is `EXPIRED`, even before the "
                + "cleanup job deletes the file (S10B-AC-03).") CompanyExportState state,
        @Schema(description = "Why a `FAILED` export stopped; null for the other states.", nullable = true)
                CompanyExportFailure failure,
        @Schema(description = "Internal comments and tasks are in the ZIP (S10B-AC-13).") boolean includeInternal,
        @Schema(description = "Private issues are in the ZIP (S10B-AC-16).") boolean includePrivate,
        CompanyExportRequester requestedBy,
        OffsetDateTime requestedAt,
        @Schema(description = "When the export became `DONE` or `FAILED`; null while it is `RUNNING`. An "
                + "`EXPIRED` export keeps the time it became `DONE`.", nullable = true) OffsetDateTime finishedAt,
        @Schema(description = "Bytes of the ZIP; null unless the export became `DONE`.", nullable = true)
                Long size) {}
