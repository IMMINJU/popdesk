package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.branding.AccentPalette;
import io.popdesk.helpdesk.branding.BrandingService;
import io.popdesk.helpdesk.web.publicapi.AccentColors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The branding of the installation (BRAND-1 to BRAND-3), for admins only: the display name and the accent
 * color are saved together with a version, each logo on its own and without one (S9-AC-18). The role is
 * checked before anything in the request, so an agent always gets 403 (S9-AC-35). Customers never reach
 * this path: a portal host answers 404 and a console host 401, as for every console path.
 */
@Tag(name = "Branding")
@RestController
@RequestMapping("/api/console/branding")
class ConsoleBrandingController {

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only, agents too (S9-AC-35).";
    static final String THEME = "`light` or `dark`.";
    static final String NO_THEME = "`NOT_FOUND`: `theme` is not `light` or `dark`.";

    private final BrandingService branding;

    ConsoleBrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @Operation(operationId = "getBranding", summary = "The branding",
            description = "The display name, the accent color as saved, the values made from it and their "
                    + "contrast, and the logo of each theme (S9-AC-06). Checks, in this order: role (403).")
    @ApiResponse(responseCode = "200", description = "The branding.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @GetMapping
    Branding getBranding() {
        return branding(branding.get(CurrentAccount.get().viewer()));
    }

    @Operation(operationId = "updateBranding", summary = "Change the display name and the accent color",
            description = "Saves both and makes the next version (S9-AC-07). Sending what the branding has "
                    + "already is 200 with the branding as it is, no new version, and no audit entry. A change "
                    + "leaves an audit entry `BRANDING_CHANGED` with the target `{\"type\": \"BRANDING\"}` and, "
                    + "in `changes`, `displayName` or `accent` from before to after for each that changed; an "
                    + "accent is its `#RRGGBB` or null (S9-AC-20). Logos are not touched. Checks, in this order: "
                    + "role (403), the body (400), `version` (409), so a body that is wrong is 400 even with an "
                    + "old version (S9-AC-08).")
    @ApiResponse(responseCode = "200", description = "The branding as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`, nothing changed; `fields` lists all: "
            + "`displayName` missing or empty (`REQUIRED`), only whitespace (`BLANK`), over 50 characters "
            + "(`TOO_LONG`), or with a line break, a control character, or a direction mark (`INVALID_FORMAT`, "
            + "S9-AC-09); `accent` not `#` and six hexadecimal digits (`INVALID_FORMAT`, S9-AC-10) or a color "
            + "whose made values miss 4.5:1 on a theme's backgrounds (`LOW_CONTRAST`, S9-AC-11); `version` "
            + "missing (`REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "409", description = "`VERSION_CONFLICT`: `version` is not the branding's current "
            + "one, for example because another admin saved first; nothing changed, even when the values are the "
            + "ones it has (S9-AC-08). Read the branding again.")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Branding updateBranding(@RequestBody UpdateBrandingRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        return branding(branding.update(admin.viewer(), admin.actor(), new BrandingService.Change(request.version(),
                request.displayName(), request.accent())));
    }

    @Operation(operationId = "previewAccent", summary = "What an accent color would make",
            description = "The values popdesk would make from the color, their contrast, and whether "
                    + "`updateBranding` takes it; nothing is saved (S9-AC-12). Checks, in this order: role "
                    + "(403), `color` (400).")
    @ApiResponse(responseCode = "200", description = "The made values.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `color` missing or empty (`REQUIRED`), or not `#` "
            + "and six hexadecimal digits (`INVALID_FORMAT`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @GetMapping("/accent")
    AccentPreview previewAccent(
            @Parameter(description = "`#RRGGBB`, either letter case; the `#` is sent as `%23`.")
                    @RequestParam(required = false) @Nullable String color) {
        AccentPalette.Palette palette = branding.preview(CurrentAccount.get().viewer(), color);
        return new AccentPreview(AccentColors.of(palette), contrast(palette), palette.acceptable());
    }

    @Operation(operationId = "uploadBrandingLogo", summary = "Set the logo of a theme",
            description = "Takes a PNG, JPEG, or WebP file of up to 512 KB, judged by its content, never SVG. "
                    + "popdesk turns it by its EXIF orientation, scales it to fit 512 by 128 px (never larger), "
                    + "and keeps only that copy as a PNG without metadata, not the uploaded file (S9-AC-15). The "
                    + "new logo replaces the theme's logo; the branding's `version` stays (S9-AC-18). `PUT`, "
                    + "unlike a profile photo's `POST`: each theme has one logo, put in place. A new logo leaves "
                    + "an audit entry `BRANDING_LOGO_CHANGED` with the target `{\"type\": \"BRANDING\", "
                    + "\"theme\": \"LIGHT\"}` (or `DARK`) and, in `changes`, `logo` from the fingerprint before, "
                    + "or null, to the new one; the same file again leaves none (S9-AC-21). Checks, in this "
                    + "order: role (403), the `file` part (400, looked for before anything else in the request), "
                    + "rate limit (429, refused uploads count too), `theme` (404), the size of the file (413), a "
                    + "place for decoding (503, profile photos and logos together), then the file's first bytes "
                    + "and pixel count (400); a refused file leaves the logo as it was (S9-AC-16).")
    @ApiResponse(responseCode = "200", description = "The logo as stored.")
    @ApiResponse(responseCode = "400", description = "`UNSUPPORTED_IMAGE`: not one of the three formats by its "
            + "first bytes (an SVG, a GIF, or an HTML file named `.png` too), or it cannot be decoded. "
            + "`IMAGE_TOO_LARGE`: width times height is over 25 million pixels for PNG and JPEG, 12 million for "
            + "WebP, as for a profile photo (S9-AC-16). `INVALID_INPUT`: no `file` part (`REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_THEME)
    @ApiResponse(responseCode = "413", description = "`PAYLOAD_TOO_LARGE`: the file is over 512 KB (524,288 "
            + "bytes, S9-AC-16).")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 10 logo uploads in 60 seconds, "
            + "refused ones too; counted apart from profile photos (S9-AC-19).")
    @ApiResponse(responseCode = "503", description = "`SERVER_BUSY`: 2 images, logos and profile photos together, "
            + "are being decoded; try again after `Retry-After` (S9-AC-19).")
    @PutMapping(path = "/logos/{theme}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    BrandingLogo uploadBrandingLogo(
            @Parameter(description = THEME, schema = @Schema(type = "string", allowableValues = {"light", "dark"}))
                    @PathVariable String theme,
            @RequestPart("file") MultipartFile file) {
        CurrentAccount admin = CurrentAccount.get();
        return logo(branding.uploadLogo(admin.viewer(), admin.actor(), theme, file));
    }

    @Operation(operationId = "deleteBrandingLogo", summary = "Remove the logo of a theme",
            description = "Succeeds also when the theme has no logo, and then leaves no audit entry; otherwise "
                    + "`BRANDING_LOGO_CHANGED` with `logo` from the fingerprint to null (S9-AC-17, S9-AC-21). The "
                    + "branding's `version` stays (S9-AC-18). Checks, in this order: role (403), `theme` (404).")
    @ApiResponse(responseCode = "204", description = "The theme has no logo now.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = NO_THEME)
    @DeleteMapping("/logos/{theme}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteBrandingLogo(
            @Parameter(description = THEME, schema = @Schema(type = "string", allowableValues = {"light", "dark"}))
                    @PathVariable String theme) {
        CurrentAccount admin = CurrentAccount.get();
        branding.deleteLogo(admin.viewer(), admin.actor(), theme);
    }

    private static Branding branding(BrandingService.Settings settings) {
        return new Branding(settings.version(), settings.displayName(), settings.accent(),
                AccentColors.of(settings.palette()), settings.palette() == null ? null : contrast(settings.palette()),
                new BrandingLogos(logo(settings.light()), logo(settings.dark())));
    }

    private static AccentContrast contrast(AccentPalette.Palette palette) {
        return new AccentContrast(palette.lightContrast(), palette.darkContrast());
    }

    private static BrandingLogo logo(BrandingService.Logo logo) {
        return logo == null ? null : new BrandingLogo(logo.url(), logo.width(), logo.height());
    }
}
