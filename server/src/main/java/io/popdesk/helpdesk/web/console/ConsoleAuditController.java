package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.popdesk.helpdesk.audit.AuditAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The audit log: reading, exporting, and checking its hash chain (AUDIT-3, AUDIT-7, AUDIT-8). Admins
 * only, and read only: no endpoint changes or deletes an entry (S1B-AC-08). Contract shells for slice
 * 1b; every endpoint answers 501 until the slice builds it.
 */
@Tag(name = "Audit log")
@RestController
@RequestMapping("/api/console/audit-log")
class ConsoleAuditController {

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only.";
    static final String FILTER_ERRORS = "unknown `action` or `targetType` (`UNKNOWN_VALUE`), `from` after `to` "
            + "(field `from`, `FROM_AFTER_TO`), `from` or `to` not a time (`INVALID_FORMAT`)";

    static final String ACTION = "Only entries of these kinds. Repeat the parameter for several.";
    static final String ACTOR = "Only entries done by this account. `0` is popdesk itself.";
    static final String TARGET_TYPE = "Only entries whose target has this `type`.";
    static final String TARGET_ACCOUNT = "Only entries whose target names this account (`accountId` of the "
            + "target). Without `targetType` this also finds a company's membership and assignment entries of "
            + "the account.";
    static final String TARGET_CODE = "Only entries whose target names this customer company (`code` of the "
            + "target).";
    static final String FROM = "Only entries at or after this time (TIME-1).";
    static final String TO = "Only entries before this time; the time itself is left out.";

    @Operation(operationId = "listAuditEntries", summary = "Audit log",
            description = "Newest first; entries of the same time by `seq`, highest first (S1B-AC-01). The "
                    + "filters narrow it together (S1B-AC-03 to S1B-AC-06). No entry holds a password, a token, or "
                    + "a link (S1B-AC-07).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range "
            + "(`OUT_OF_RANGE`), " + FILTER_ERRORS + ".")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @GetMapping
    Paged<AuditEntry> listAuditEntries(
            @Parameter(description = ACTION) @RequestParam(required = false) @Nullable List<AuditAction> action,
            @Parameter(description = ACTOR) @RequestParam(required = false) @Nullable Long actorAccountId,
            @Parameter(description = TARGET_TYPE) @RequestParam(required = false) @Nullable AuditTargetType targetType,
            @Parameter(description = TARGET_ACCOUNT) @RequestParam(required = false) @Nullable Long targetAccountId,
            @Parameter(description = TARGET_CODE) @RequestParam(required = false) @Nullable String targetCode,
            @Parameter(description = FROM) @RequestParam(required = false) @Nullable OffsetDateTime from,
            @Parameter(description = TO) @RequestParam(required = false) @Nullable OffsetDateTime to,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "exportAuditLog", summary = "Export the audit log",
            description = "Every entry the filters of `listAuditEntries` find, in chain order (`seq`, lowest "
                    + "first), streamed as a file (S1B-AC-23, S1B-AC-27). `JSONL` has one JSON object per line "
                    + "with `id`, `seq`, `prevHash`, `hash`, `action`, `actor`, `target`, `changes`, "
                    + "`occurredAt`: everything needed to compute each hash again (S1B-AC-24). `CSV` has the "
                    + "same columns, `actor` as three columns and `target` and `changes` as JSON text; a value "
                    + "that starts with `=`, `+`, `-`, `@`, a tab, or a line break gets `'` in front "
                    + "(S1B-AC-25). The export itself is recorded as `AUDIT_EXPORTED` when it starts and is not "
                    + "in its own file (S1B-AC-26): its target is `{\"type\": \"AUDIT_LOG\"}` and its changes are "
                    + "`format`, `count` (entries found when it started), and `filter` (an object with only the "
                    + "filters that were sent; `{}` without any). The response has `Cache-Control: no-store`.")
    @ApiResponse(responseCode = "200", description = "The file.",
            headers = {
                @Header(name = HttpHeaders.CONTENT_TYPE, description = "`text/csv; charset=UTF-8` for `CSV`, "
                        + "`application/x-ndjson` for `JSONL`.", schema = @Schema(type = "string")),
                @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = "`attachment` with a file name "
                        + "that ends in `.csv` or `.jsonl`.", schema = @Schema(type = "string"))
            },
            content = {
                @Content(mediaType = "text/csv", schema = @Schema(type = "string")),
                @Content(mediaType = "application/x-ndjson", schema = @Schema(type = "string"))
            })
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `format` missing (`REQUIRED`) or unknown "
            + "(`UNKNOWN_VALUE`), " + FILTER_ERRORS + ".")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 2 exports are running; try again after "
            + "`Retry-After` (S1B-AC-28).",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again.",
                    schema = @Schema(type = "integer")))
    @GetMapping("/export")
    ResponseEntity<StreamingResponseBody> exportAuditLog(
            @Parameter(description = "The file format.") @RequestParam AuditExportFormat format,
            @Parameter(description = ACTION) @RequestParam(required = false) @Nullable List<AuditAction> action,
            @Parameter(description = ACTOR) @RequestParam(required = false) @Nullable Long actorAccountId,
            @Parameter(description = TARGET_TYPE) @RequestParam(required = false) @Nullable AuditTargetType targetType,
            @Parameter(description = TARGET_ACCOUNT) @RequestParam(required = false) @Nullable Long targetAccountId,
            @Parameter(description = TARGET_CODE) @RequestParam(required = false) @Nullable String targetCode,
            @Parameter(description = FROM) @RequestParam(required = false) @Nullable OffsetDateTime from,
            @Parameter(description = TO) @RequestParam(required = false) @Nullable OffsetDateTime to) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "headAuditLogExport", summary = "Headers of an audit log export",
            description = "Checks the same input as `exportAuditLog` and answers with the headers it would "
                    + "send, without the body. The console calls it before it starts the download. It records "
                    + "nothing in the audit log and takes none of the 2 export places; it only answers 503 while "
                    + "both are taken.")
    @ApiResponse(responseCode = "200", description = "The export would start.",
            headers = {
                @Header(name = HttpHeaders.CONTENT_TYPE, description = "`text/csv; charset=UTF-8` for `CSV`, "
                        + "`application/x-ndjson` for `JSONL`.", schema = @Schema(type = "string")),
                @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = "`attachment` with a file name "
                        + "that ends in `.csv` or `.jsonl`.", schema = @Schema(type = "string"))
            })
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`, as for `exportAuditLog`. A `HEAD` response "
            + "has no body.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 2 exports are running; try again after "
            + "`Retry-After` (S1B-AC-28). A `HEAD` response has no body.",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again.",
                    schema = @Schema(type = "integer")))
    @RequestMapping(path = "/export", method = RequestMethod.HEAD)
    ResponseEntity<Void> headAuditLogExport(
            @Parameter(description = "The file format.") @RequestParam AuditExportFormat format,
            @Parameter(description = ACTION) @RequestParam(required = false) @Nullable List<AuditAction> action,
            @Parameter(description = ACTOR) @RequestParam(required = false) @Nullable Long actorAccountId,
            @Parameter(description = TARGET_TYPE) @RequestParam(required = false) @Nullable AuditTargetType targetType,
            @Parameter(description = TARGET_ACCOUNT) @RequestParam(required = false) @Nullable Long targetAccountId,
            @Parameter(description = TARGET_CODE) @RequestParam(required = false) @Nullable String targetCode,
            @Parameter(description = FROM) @RequestParam(required = false) @Nullable OffsetDateTime from,
            @Parameter(description = TO) @RequestParam(required = false) @Nullable OffsetDateTime to) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "verifyAuditLog", summary = "Check the audit log's hash chain",
            description = "Computes every hash again from the start of the chain, or from the kept starting point "
                    + "when old entries were removed by the retention period (S1B-AC-14, S1B-AC-17). A broken "
                    + "chain is a 200 with `valid` false: `ENTRY_MISMATCH` names the first entry that does not "
                    + "hold (a changed entry, or the entry after a removed one), `HEAD_MISMATCH` means the last "
                    + "entries were removed (S1B-AC-15, S1B-AC-16, S1B-AC-42). A valid result does not rule out "
                    + "that the whole chain was written again. One check runs at a time.")
    @ApiResponse(responseCode = "200", description = "The result.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "409", description = "`AUDIT_VERIFICATION_RUNNING`: a check is already running "
            + "(S1B-AC-18).")
    @PostMapping("/verify")
    AuditVerification verifyAuditLog() {
        throw new NotImplementedException();
    }
}
