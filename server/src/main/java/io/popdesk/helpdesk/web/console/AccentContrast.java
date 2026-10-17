package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The lowest WCAG 2.1 contrast of each theme's `accent` over the backgrounds it is text on, rounded down to
 * two decimals: 4.499 is 4.49 (S9-AC-12, ADR D-61). A color is taken when both are 4.5 or more.
 */
public record AccentContrast(
        @Schema(description = "Light theme: over `#FFFFFF`, `#F7F6F3`, `#F1F0ED`, `#F4F3F0`, and the made "
                + "`accentSubtle`.") double light,
        @Schema(description = "Dark theme: over `#191817`, `#201F1D`, `#2A2826`, `#121110`, `#24221F`, and the "
                + "made `accentSubtle`.") double dark) {}
