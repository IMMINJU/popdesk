package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.web.publicapi.AccentColors;
import io.popdesk.helpdesk.web.publicapi.ThemeColors;
import io.swagger.v3.oas.annotations.media.Schema;

/** The branding of the installation as an admin edits it (BRAND-1, S9-AC-06). */
public record Branding(
        @Schema(description = "Goes up by 1 when the display name or the accent color changes, not when a logo "
                + "does (S9-AC-18). Send it back with `updateBranding`.") long version,
        @Schema(description = "1 to 50 characters; `popdesk` by default.") String displayName,
        @Schema(description = "The accent color as saved, `#RRGGBB` in capitals; null for the app's own colors.",
                pattern = ThemeColors.HEX, nullable = true) String accent,
        @Schema(description = "The values made from `accent`; null without one.", nullable = true)
                AccentColors colors,
        @Schema(description = "The contrast of `colors`; null without an accent color.", nullable = true)
                AccentContrast contrast,
        BrandingLogos logos) {}
