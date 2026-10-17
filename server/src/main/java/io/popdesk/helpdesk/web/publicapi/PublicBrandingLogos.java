package io.popdesk.helpdesk.web.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where the logos are (BRAND-2, S9-AC-03). With a logo for one theme only, both fields have its address;
 * with none, both are null.
 */
public record PublicBrandingLogos(
        @Schema(description = "`/api/public/branding/logos/{theme}?v={fingerprint}` for the light theme; null "
                + "without a logo.", nullable = true) String light,
        @Schema(description = "The same for the dark theme; null without a logo.", nullable = true) String dark) {}
