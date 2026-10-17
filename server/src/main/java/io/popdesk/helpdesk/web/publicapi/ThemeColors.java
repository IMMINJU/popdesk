package io.popdesk.helpdesk.web.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The four values of the accent group for one theme (BRAND-3), each `#RRGGBB` in capitals. They change
 * only these four; focus rings, status colors, and error colors stay as they are (S9-AC-14, ADR D-50).
 */
public record ThemeColors(
        @Schema(description = "Text, links, and the main button.", pattern = ThemeColors.HEX) String accent,
        @Schema(description = "The main button under the pointer.", pattern = ThemeColors.HEX) String accentHover,
        @Schema(description = "Text on `accent`: the theme's background color.", pattern = ThemeColors.HEX)
                String onAccent,
        @Schema(description = "A light background of the accent, for example the chosen row.",
                pattern = ThemeColors.HEX) String accentSubtle) {

    public static final String HEX = "^#[0-9A-F]{6}$";
}
