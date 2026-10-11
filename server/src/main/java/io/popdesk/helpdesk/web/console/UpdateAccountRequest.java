package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). The name cannot be cleared, so {@code null} is refused; it
 * is read as JSON to tell {@code null} from a field left out.
 */
public record UpdateAccountRequest(@Schema(type = "string", description = "1 to 50 characters.") JsonNode name) {}
