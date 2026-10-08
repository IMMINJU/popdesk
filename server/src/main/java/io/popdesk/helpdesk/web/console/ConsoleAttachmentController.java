package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
import io.popdesk.Paged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachments, always under their issue (docs/integrate/conventions.md "Attachment paths"). Every
 * endpoint answers one identical 404 for an unknown ID, an issue the caller cannot see, an
 * attachment of another issue, and a pending or deleted attachment (S0-AC-102).
 */
@Tag(name = "Attachments")
@RestController
@RequestMapping("/api/console/issues/{key}/attachments")
class ConsoleAttachmentController {

    static final String NOT_FOUND = "`NOT_FOUND`: one identical response for an unknown ID, an issue the caller "
            + "cannot see, an attachment of another issue, and a pending or deleted attachment (S0-AC-102).";

    @Operation(operationId = "uploadAttachment", summary = "Attach a file",
            description = "Attaches a file to the issue, or to the caller's own comment with `commentId` "
                    + "(S0-AC-56). A file on an internal comment counts only against the team quota (S0-AC-57).")
    @ApiResponse(responseCode = "201", description = "Stored.")
    @ApiResponse(responseCode = "400", description = "`FILE_EMPTY`, `FILE_TOO_LARGE` (over 10 MB), "
            + "`ISSUE_ATTACHMENTS_TOO_LARGE` (public attachments of the issue over 50 MB), "
            + "`ATTACHMENT_QUOTA_EXCEEDED` (team quota), `INVALID_INPUT` (field `file` `TOO_LONG`: name over 255; "
            + "field `commentId` `UNKNOWN_VALUE`: not the caller's comment on this issue) (S0-AC-57, S0-AC-58). "
            + "`ISSUE_CLOSED`.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the request is over the upload limit.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 30 uploads in 60 seconds "
            + "(S0-AC-65).")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 16 uploads are running; try again after "
            + "`Retry-After` (S0-AC-99).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Attachment uploadAttachment(
            @PathVariable String key,
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Attach to this comment of the caller instead of the issue.")
                    @RequestPart(name = "commentId", required = false) @Nullable Long commentId) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "listAttachments", summary = "Attachments of an issue",
            description = "Files on the issue and on all its comments, oldest first. Deleted attachments stay as "
                    + "traces (`deleted`).")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such issue, or the caller cannot see it.")
    @GetMapping
    Paged<Attachment> listAttachments(
            @PathVariable String key,
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "downloadAttachment", summary = "Download a file",
            description = "Always `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` "
                    + "(S0-AC-59). Takes `Range` requests.")
    @ApiResponse(responseCode = "200", description = "The file.", content = @Content(
            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Part of the file, for a `Range` request.", content = @Content(
            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 16 downloads are running; try again after "
            + "`Retry-After` (S0-AC-99).")
    @GetMapping("/{id}")
    ResponseEntity<Resource> downloadAttachment(
            @PathVariable String key,
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) @Nullable String range) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "headAttachment", summary = "Headers of a file download",
            description = "The headers `downloadAttachment` would send, without the body.")
    @ApiResponse(responseCode = "200", description = "The file exists and the caller may download it.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @RequestMapping(path = "/{id}", method = RequestMethod.HEAD)
    ResponseEntity<Void> headAttachment(@PathVariable String key, @PathVariable UUID id) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "deleteAttachment", summary = "Delete a file",
            description = "The uploader or an admin may delete. The list keeps a trace (S0-AC-61, COM-7).")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: an agent who did not upload it. "
            + "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAttachment(@PathVariable String key, @PathVariable UUID id) {
        throw new NotImplementedException();
    }
}
