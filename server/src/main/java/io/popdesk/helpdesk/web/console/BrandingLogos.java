package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The logo of each theme as stored (S9-AC-06). Unlike the public answer, a theme without its own logo is
 * null here even when the other theme has one.
 */
public record BrandingLogos(
        @Schema(description = "Null without a logo for the light theme.", nullable = true) BrandingLogo light,
        @Schema(description = "Null without a logo for the dark theme.", nullable = true) BrandingLogo dark) {}
