package io.popdesk.helpdesk.web.portal;

import io.swagger.v3.oas.annotations.media.Schema;

/** A field left out keeps its value (ADR D-44). Neither field can be cleared. */
public record UpdateMeRequest(
        @Schema(description = "1 to 50 characters.") String name,
        @Schema(description = "Language of the screens and of the account's email.", allowableValues = {"ko", "en"})
                String locale) {}
