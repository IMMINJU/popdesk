package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** A field left out keeps its value (ADR D-44). Neither field can be cleared; the code never changes. */
public record UpdateCompanyRequest(
        @Schema(description = "1 to 100 characters.") String name,
        @Schema(description = "1 to 100 characters.") String legalName) {}
