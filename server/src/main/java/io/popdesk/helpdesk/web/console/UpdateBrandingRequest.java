package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** The display name and the accent color as they should be, with the version they were read at (S9-AC-07). */
public record UpdateBrandingRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The `version` of the branding as the caller read it (S9-AC-08).") Long version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 50 characters after trimming "
                + "and NFC; no line breaks, control characters, or direction marks (S9-AC-09).") String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "`#RRGGBB`, either letter case, kept "
                + "in capitals; null for the app's own colors (S9-AC-10, S9-AC-11).", nullable = true)
                String accent) {}
