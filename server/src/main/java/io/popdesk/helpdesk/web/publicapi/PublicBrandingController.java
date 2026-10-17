package io.popdesk.helpdesk.web.publicapi;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The branding of the installation before sign-in (BRAND-4, BRAND-5): both hosts, no session, no rate
 * limit. One branding for the whole installation (BRAND-1, NG-07).
 */
@Tag(name = "Branding")
@RestController
@RequestMapping("/api/public/branding")
class PublicBrandingController {

    @Operation(operationId = "getPublicBranding", summary = "The branding",
            description = "The display name, the values made from the accent color, and where the logos are "
                    + "(S9-AC-01, S9-AC-02). An installation that never saved a branding answers the default: "
                    + "`popdesk`, `colors` null, both logos null (S9-AC-05). `Cache-Control: no-store` like any "
                    + "other API response.")
    @ApiResponse(responseCode = "200", description = "The branding.")
    @ApiResponse(responseCode = "503", description = "`SETUP_REQUIRED`: there is no first admin yet.")
    @GetMapping
    PublicBranding getPublicBranding() {
        // The contract of slice 9 answers the default; reading the stored branding comes with its build.
        return new PublicBranding("popdesk", null, new PublicBrandingLogos(null, null));
    }

    @Operation(operationId = "getBrandingLogo", summary = "A logo",
            description = "The logo of one theme, always PNG, with `X-Content-Type-Options: nosniff` and a strong "
                    + "`ETag`, its fingerprint (S9-AC-04). With `v` equal to that fingerprint, the address "
                    + "`getPublicBranding` gives, it is `Cache-Control: public, max-age=31536000, immutable`; "
                    + "with another `v` or none, `no-cache`. A new logo has a new fingerprint, so the address "
                    + "changes with it. `HEAD` answers the same headers.")
    @ApiResponse(responseCode = "200", description = "The logo, with its `ETag`.", content = @Content(
            mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "304", description = "The logo still has the `ETag` sent in `If-None-Match`. No "
            + "body.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: the theme has no logo, or `theme` is not "
            + "`light` or `dark`. The logo of the other theme is never given instead; `getPublicBranding` "
            + "already points both themes at the one there is (S9-AC-03).")
    @ApiResponse(responseCode = "503", description = "`SETUP_REQUIRED`: there is no first admin yet.")
    @GetMapping("/logos/{theme}")
    ResponseEntity<Resource> getBrandingLogo(
            @Parameter(description = "`light` or `dark`.",
                    schema = @Schema(type = "string", allowableValues = {"light", "dark"}))
                    @PathVariable String theme,
            @Parameter(description = "The fingerprint the address carries; with it the logo may be kept a year.")
                    @RequestParam(required = false) @Nullable String v,
            @Parameter(description = "The `ETag` of the copy the caller has.")
                    @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatch) {
        throw new NotImplementedException();
    }
}
