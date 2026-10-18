package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.privacy.CompanyExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Company exports (PII-8), for admins only (S10B-AC-10): one company's data as a ZIP written in the
 * background, downloaded for 24 hours by any admin. The file has a path of its own, by the export's
 * ID, so a download needs no company code.
 */
@Tag(name = "Companies")
@RestController
class ConsoleCompanyExportController {

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only, also for an agent assigned to the company "
            + "(S10B-AC-10).";
    static final String NO_FILE = "`NOT_FOUND`: no such export, or its ZIP cannot be downloaded: the export is "
            + "`RUNNING`, `FAILED`, or `EXPIRED`, or it became `DONE` over 24 hours ago, even before the cleanup "
            + "job deleted the file (S10B-AC-07). One body for all of these.";
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final CompanyExportService exports;

    ConsoleCompanyExportController(CompanyExportService exports) {
        this.exports = exports;
    }

    static final String CONTENT_DISPOSITION = "`attachment; filename=\"popdesk-{code}-{yyyy-MM-dd}.zip\"`, with the "
            + "company code and the date the export was asked for, in UTC.";

    @Operation(operationId = "createCompanyExport", summary = "Export a company's data",
            description = "Starts writing one ZIP in the background with the company's issues, public comments, "
                    + "attachments, and member customers (email and name), and answers at once with the export "
                    + "as `RUNNING` (S10B-AC-01, S10B-AC-11 to S10B-AC-17). An `ARCHIVED` or `SUSPENDED` company "
                    + "can be exported too. One export runs at a time in the whole installation (S10B-AC-02). "
                    + "When the ZIP is ready the export becomes `DONE` and the admin who asked gets "
                    + "`COMPANY_EXPORT_READY` (S10B-AC-04); when it fails it becomes `FAILED` and every admin "
                    + "gets `COMPANY_EXPORT_FAILED` (S10B-AC-05). Recorded as `COMPANY_EXPORTED` (S10B-AC-09). "
                    + "Checks, in this order: role (403), the company (404), the body (400), another export "
                    + "running (409).")
    @ApiResponse(responseCode = "202", description = "Started.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `includeInternal` or `includePrivate` "
            + "missing or `null` (`REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company.")
    @ApiResponse(responseCode = "409", description = "`EXPORT_RUNNING`: another company export is `RUNNING`, of "
            + "this company or any other (S10B-AC-02). Nothing was started.")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: the app has lost its single-instance lock and "
            + "is taking it again, for example after the database connection broke; try again after "
            + "`Retry-After` (S10B-AC-02). Nothing was started.",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds to wait before trying again.",
                    schema = @Schema(type = "integer")))
    @PostMapping("/api/console/companies/{code}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CompanyExport createCompanyExport(@PathVariable String code, @RequestBody CreateCompanyExportRequest request) {
        return export(exports.create(CurrentAccount.get(), code, request.includeInternal(),
                request.includePrivate()));
    }

    @Operation(operationId = "listCompanyExports", summary = "Exports of a company",
            description = "The company's last 50 exports, the latest asked for first (S10B-AC-03). A `DONE` "
                    + "export whose ZIP is over 24 hours old is `EXPIRED`.")
    @ApiResponse(responseCode = "200", description = "The exports.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such company.")
    @GetMapping("/api/console/companies/{code}/exports")
    List<CompanyExport> listCompanyExports(@PathVariable String code) {
        return exports.list(CurrentAccount.get(), code).stream().map(ConsoleCompanyExportController::export).toList();
    }

    @Operation(operationId = "downloadCompanyExport", summary = "Download a company export",
            description = "The ZIP of a `DONE` export, streamed, for any admin, not only the one who asked "
                    + "(S10B-AC-07). Recorded as `COMPANY_EXPORT_DOWNLOADED` (S10B-AC-09). The response has "
                    + "`Cache-Control: no-store`.")
    @ApiResponse(responseCode = "200", description = "The file.",
            headers = @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = CONTENT_DISPOSITION,
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NO_FILE)
    @GetMapping("/api/console/company-exports/{id}/file")
    ResponseEntity<StreamingResponseBody> downloadCompanyExport(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        CompanyExportService.Download download = exports.download(CurrentAccount.get(), id);
        InputStream contents = download.contents();
        return ResponseEntity.ok().headers(headers(download.file())).body(out -> {
            try (contents) {
                contents.transferTo(out);
            }
        });
    }

    @Operation(operationId = "headCompanyExport", summary = "Headers of a company export download",
            description = "Answers as `downloadCompanyExport` would, without the body. The console calls it "
                    + "before it starts the download. It records nothing in the audit log.")
    @ApiResponse(responseCode = "200", description = "The download would start.",
            headers = @Header(name = HttpHeaders.CONTENT_DISPOSITION, description = CONTENT_DISPOSITION,
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @ApiResponse(responseCode = "404", description = NO_FILE + " A `HEAD` response has no body.")
    @RequestMapping(path = "/api/console/company-exports/{id}/file", method = RequestMethod.HEAD)
    ResponseEntity<Void> headCompanyExport(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        return ResponseEntity.ok().headers(headers(exports.check(CurrentAccount.get(), id))).build();
    }

    /** The headers of a download (S10B-AC-07): the ZIP, its name with the UTC date it was asked for, no-store. */
    private static HttpHeaders headers(CompanyExportService.ZipFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(ZIP);
        headers.setContentLength(file.size());
        headers.setContentDisposition(ContentDisposition.attachment().filename("popdesk-" + file.companyCode() + "-"
                + file.requestedAt().atZone(ZoneOffset.UTC).toLocalDate() + ".zip").build());
        headers.setCacheControl(CacheControl.noStore());
        return headers;
    }

    private static CompanyExport export(CompanyExportService.Export e) {
        return new CompanyExport(e.id(), CompanyExportState.valueOf(e.state()),
                e.failure() == null ? null : CompanyExportFailure.valueOf(e.failure()), e.includeInternal(),
                e.includePrivate(), new CompanyExportRequester(e.requestedById(), e.requestedByName()),
                ConsoleIssueController.utc(e.requestedAt()), ConsoleIssueController.utc(e.finishedAt()), e.size());
    }
}
