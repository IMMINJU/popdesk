package io.popdesk.helpdesk.web.portal;

import io.popdesk.NotImplementedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Photos")
@RestController
class PortalPhotoController {

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
    @PostMapping(path = "/api/portal/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Me uploadMyPhoto(@RequestPart("file") MultipartFile file) {
        throw new NotImplementedException();
    }

    @Operation(operationId = "deleteMyPhoto", summary = "Delete the caller's profile photo",
            description = "Succeeds also when there is no photo (S1A-AC-24).")
    @ApiResponse(responseCode = "204", description = "The account has no photo now.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`CSRF_TOKEN_INVALID`.")
    @DeleteMapping("/api/portal/me/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMyPhoto() {
        throw new NotImplementedException();
    }

    @Operation(operationId = "getPhoto", summary = "Profile photo of a customer",
            description = "The caller's own photo, or that of a customer of a company the caller belongs to "
                    + "(S1A-AC-25). Never a staff photo (S1A-AC-26). Always PNG, with "
                    + "`X-Content-Type-Options: nosniff` and `Cache-Control: private` (S1A-AC-87).")
    @ApiResponse(responseCode = "200", description = "The photo.", content = @Content(
            mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such account, a customer of another company, "
            + "an agent or admin, or an account without a photo. One body for all.")
    @GetMapping("/api/portal/photos/{accountId}")
    ResponseEntity<Resource> getPhoto(@PathVariable long accountId) {
        throw new NotImplementedException();
    }
}
