package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** A field left out keeps its value (ADR D-44). Neither field can be cleared; the code never changes. */
public record UpdateCompanyRequest(
        @Schema(description = "1 to 100 characters.") String name,
        @Schema(description = "1 to 100 characters.") String legalName,
        // Not a field of the contract: read only to refuse a request that sends one (S1A-AC-43).
        @Schema(hidden = true) JsonNode code) {}
