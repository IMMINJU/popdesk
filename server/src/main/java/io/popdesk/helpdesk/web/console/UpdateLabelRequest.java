package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). Neither field can be cleared, so {@code null} is refused;
 * the fields are read as JSON to tell {@code null} from a field left out.
 */
public record UpdateLabelRequest(
        @Schema(type = "string", description = "1 to 30 characters, as for `createLabel`. Changing only the case "
                + "of the label's own name works (S6A-AC-03).") JsonNode name,
        @Schema(type = "string", description = "`#` and six hexadecimal digits, as for `createLabel`.")
                JsonNode color) {}
