package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). Neither field can be cleared, so {@code null} is refused;
 * the fields are read as JSON to tell {@code null} from a field left out.
 */
public record UpdateMeRequest(
        @Schema(type = "string", description = "1 to 50 characters.") JsonNode name,
        @Schema(type = "string", description = "Language of the account's email. The screens are in Korean for now.",
                allowableValues = {"ko", "en"}) JsonNode locale) {}
