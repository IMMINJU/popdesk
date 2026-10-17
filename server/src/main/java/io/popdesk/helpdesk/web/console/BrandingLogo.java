package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** One stored logo (S9-AC-06, S9-AC-15): the PNG popdesk made, scaled to fit 512 by 128 px. */
public record BrandingLogo(
        @Schema(description = "Its public address, `/api/public/branding/logos/{theme}?v={fingerprint}`, the "
                + "same as in `getPublicBranding`.") String url,
        @Schema(description = "In pixels, 1 to 512.") int width,
        @Schema(description = "In pixels, 1 to 128.") int height) {}
