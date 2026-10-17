package io.popdesk.helpdesk.web.publicapi;

import io.popdesk.helpdesk.branding.AccentPalette;

/**
 * The values popdesk makes from one accent color, for the light and the dark theme (BRAND-3,
 * S9-AC-13): the light `accent` is the color as given, the dark one has its OKLCH lightness moved. The
 * same color always gives the same values.
 */
public record AccentColors(ThemeColors light, ThemeColors dark) {

    /** The values of a palette; null for none. */
    public static AccentColors of(AccentPalette.Palette palette) {
        return palette == null ? null : new AccentColors(theme(palette.light()), theme(palette.dark()));
    }

    private static ThemeColors theme(AccentPalette.Theme theme) {
        return new ThemeColors(theme.accent(), theme.accentHover(), theme.onAccent(), theme.accentSubtle());
    }
}
