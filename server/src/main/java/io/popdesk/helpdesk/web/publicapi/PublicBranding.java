package io.popdesk.helpdesk.web.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The branding of the installation as anyone may read it before sign-in (BRAND-5, S9-AC-01): nothing
 * else, no version, who changed it, contrast, or logo size (S9-AC-02).
 */
public record PublicBranding(
        @Schema(description = "1 to 50 characters; `popdesk` until an admin changes it (S9-AC-05).")
                String displayName,
        @Schema(description = "The values made from the accent color; null without one, and the app's own "
                + "colors apply.", nullable = true) AccentColors colors,
        PublicBrandingLogos logos) {}
