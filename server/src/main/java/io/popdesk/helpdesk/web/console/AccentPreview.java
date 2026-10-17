package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.web.publicapi.AccentColors;
import io.swagger.v3.oas.annotations.media.Schema;

/** What one accent color would make, without saving it (S9-AC-12). */
public record AccentPreview(
        AccentColors colors,
        AccentContrast contrast,
        @Schema(description = "Both contrasts are 4.5 or more, so `updateBranding` takes the color "
                + "(S9-AC-11).") boolean acceptable) {}
