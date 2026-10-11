package io.popdesk.helpdesk.web.console;

import io.popdesk.NotImplementedException;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Photos")
@RestController
class ConsolePhotoController {

    @Operation(operationId = "uploadMyPhoto", summary = "Set the caller's profile photo",
            description = "Takes a PNG, JPEG, or WebP file of up to 2 MB, judged by its content. The server keeps "
                    + "only a copy scaled to fit 256 px and encoded again, without the original or its metadata "
                    + "(S1A-AC-20, S1A-AC-21). A new photo replaces the old one.")
    @ApiResponse(responseCode = "201", description = "Stored.")
    @ApiResponse(responseCode = "400", description = "`UNSUPPORTED_IMAGE`: not one of the three formats, or it "
            + "cannot be decoded (S1A-AC-22). `IMAGE_TOO_LARGE`: width times height is over 25 million pixels for "
            + "PNG and JPEG, 12 million for WebP (S1A-AC-23). `INVALID_INPUT`: no `file` part (`REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the file is over 2 MB (S1A-AC-22).")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 10 uploads in 60 seconds "
            + "(S1A-AC-28).")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 2 photos are being decoded; try again after "
            + "`Retry-After` (S1A-AC-88).")
    @PostMapping(path = "/api/console/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Me uploadMyPhoto(@RequestPart("file") MultipartFile file) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "deleteMyPhoto", summary = "Delete the caller's profile photo",
            description = "Succeeds also when there is no photo (S1A-AC-24).")
    @ApiResponse(responseCode = "204", description = "The account has no photo now.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @DeleteMapping("/api/console/me/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMyPhoto() {
        throw new NotImplementedException();
    }

    @Operation(operationId = "getPhoto", summary = "Profile photo of an account",
            description = "The photo of any account the caller can see: every account for admins; for agents staff, "
                    + "and customers of the companies they are assigned to (S1A-AC-27). Always PNG, with "
                    + "`X-Content-Type-Options: nosniff`, `Cache-Control: private, no-cache`, and an `ETag` "
                    + "(S1A-AC-87): the browser asks again every time, so the caller's right to see the photo is "
                    + "checked again, and gets 304 while the photo is the same.")
    @ApiResponse(responseCode = "200", description = "The photo, with its `ETag`.", content = @Content(
            mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "304", description = "The photo still has the `ETag` sent in `If-None-Match`. No "
            + "body.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such account, the caller cannot see it, or it "
            + "has no photo.")
    @GetMapping("/api/console/photos/{accountId}")
    ResponseEntity<Resource> getPhoto(
            @PathVariable long accountId,
            @Parameter(description = "The `ETag` of the copy the caller has.")
                    @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch) {
        throw new NotImplementedException();
    }
}
