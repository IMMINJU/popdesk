package io.popdesk.helpdesk.web.publicapi;

/**
 * The values popdesk makes from one accent color, for the light and the dark theme (BRAND-3,
 * S9-AC-13): the light `accent` is the color as given, the dark one has its OKLCH lightness moved. The
 * same color always gives the same values.
 */
public record AccentColors(ThemeColors light, ThemeColors dark) {}
