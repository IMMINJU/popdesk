package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.attachment.AttachmentHeaders;
import io.popdesk.helpdesk.attachment.AttachmentRepository.AttachmentRow;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Temporary images: uploaded before the issue or comment that shows them is saved, so not under an
 * issue (docs/integrate/conventions.md "Attachment paths"). Only the caller who uploaded one reads it
 * here; once a save attached it, it is an attachment of its issue and this path no longer knows it.
 */
@Tag(name = "Attachments")
@RestController
@RequestMapping("/api/console/images")
class ConsoleImageController {

    private final AttachmentService attachments;

    ConsoleImageController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    static final String NOT_FOUND = "`NOT_FOUND`: one identical response for an unknown ID, an image another "
            + "account uploaded, and an image that is attached already, over 24 hours old, deleted, or still "
            + "being written (S5-AC-11, S5-AC-12).";

    /** The field of the requests that save an issue's content or a comment. */
    static final String IMAGE_IDS = "IDs of temporary images the caller uploaded with `uploadImage`, up to 20. The "
            + "save attaches them to the issue, and to the comment when it writes one, or attaches none of them "
            + "and saves nothing (S5-AC-20). Only these IDs are attached: the server does not look for "
            + "`attachment:{id}` in the content, and an ID the content does not name is attached too (S5-AC-25). "
            + "An ID given twice counts once. Left out, `null`, or empty: nothing is attached.";

    /** What {@code imageIds} adds to the 400 of those requests. */
    static final String IMAGE_ERRORS_400 = "With `imageIds`: `TEMPORARY_IMAGE_INVALID` (an ID is not a temporary "
            + "image the caller uploaded, or the image is attached already or over 24 hours old; one answer "
            + "whatever the reason and whichever ID, S5-AC-21), `ISSUE_ATTACHMENTS_TOO_LARGE` (with the images, "
            + "the public attachments of the issue would pass 50 MB, S5-AC-23), and `INVALID_INPUT` with field "
            + "`imageIds` `TOO_MANY` (over 20) or `INVALID_FORMAT` (not a list of strings). Nothing is saved then.";

    @Operation(operationId = "uploadImage", summary = "Upload an image for an issue's content or a comment",
            description = "Stores a PNG, JPEG, GIF, or WebP file as a temporary image of the caller and answers "
                    + "its ID, before there is an issue or a comment to put it on (S5-AC-01). Write it into the "
                    + "Markdown as `![description](attachment:{id})` and send the ID in `imageIds` of the request "
                    + "that saves the issue or the comment; that attaches it. The format is judged by the first "
                    + "bytes of the file, never by its name or the `Content-Type` sent, and decides the "
                    + "`Content-Type` the image is served with (S5-AC-02, S5-AC-03). A temporary image not "
                    + "attached within 24 hours is deleted (S5-AC-30). A caller has at most 20 temporary images "
                    + "and 50 MB of them at once; attached and expired ones do not count (S5-AC-05). Temporary "
                    + "images count against the team quota (S5-AC-06).")
    @ApiResponse(responseCode = "201", description = "Stored.")
    @ApiResponse(responseCode = "400", description = "`FILE_NOT_IMAGE`: not one of the four formats. `FILE_EMPTY`, "
            + "`FILE_TOO_LARGE` (over 10 MB), `TEMPORARY_IMAGE_LIMIT_EXCEEDED` (the caller's temporary images "
            + "would pass 20 or 50 MB), `ATTACHMENT_QUOTA_EXCEEDED` (team quota), `INVALID_INPUT` (field `file` "
            + "`REQUIRED`: no `file` part or no file name; `TOO_LONG`: name over 255) (S5-AC-04).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the request is over the upload limit.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 30 uploads in 60 seconds, counted "
            + "together with `uploadAttachment` (S5-AC-07).")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 16 uploads are running, attachments and "
            + "images together; try again after `Retry-After` (S5-AC-07).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    TemporaryImage uploadImage(@RequestPart("file") MultipartFile file) {
        return new TemporaryImage(attachments.uploadImage(CurrentAccount.get().viewer(), file));
    }

    @Operation(operationId = "downloadImage", summary = "Read a temporary image",
            description = "The contents of a temporary image, for the caller who uploaded it and nobody else, an "
                    + "admin neither (S5-AC-10, S5-AC-11): the preview of a content that is not saved yet. The "
                    + "headers are those of `downloadAttachment`, `Content-Disposition: attachment` and "
                    + "`X-Content-Type-Options: nosniff`; a browser shows it in an `<img>` all the same. Takes "
                    + "`Range` requests. Once a save attached the image, read it with `downloadAttachment` of "
                    + "its issue; this path answers 404 from then on (S5-AC-12).")
    @ApiResponse(responseCode = "200", description = "The image.", content = @Content(
            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Part of the image, for a `Range` request.", content = @Content(
            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 16 downloads are running, attachments and "
            + "images together; try again after `Retry-After` (S5-AC-07).")
    @GetMapping("/{id}")
    ResponseEntity<Resource> downloadImage(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) @Nullable String range) {
        AttachmentService.Download download = attachments.downloadTemporaryImage(CurrentAccount.get().viewer(), id);
        return ResponseEntity.ok().headers(AttachmentHeaders.of(download.attachment())).body(download.contents());
    }

    @Operation(operationId = "headImage", summary = "Headers of a temporary image",
            description = "The headers `downloadImage` would send, without the body.")
    @ApiResponse(responseCode = "200", description = "The image exists and the caller may read it.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = NOT_FOUND)
    @RequestMapping(path = "/{id}", method = RequestMethod.HEAD)
    ResponseEntity<Void> headImage(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        AttachmentRow image = attachments.temporaryImage(CurrentAccount.get().viewer(), id);
        return ResponseEntity.ok().headers(AttachmentHeaders.of(image)).contentLength(image.size()).build();
    }
}
