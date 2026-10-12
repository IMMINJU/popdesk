package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A field left out keeps its value (ADR D-44). The fields are read as JSON to tell {@code null} from a
 * field left out. Whether the comment is internal never changes (S4-AC-07).
 */
public record UpdateCommentRequest(
        @Schema(type = "string", description = "Markdown, 1 to 4,000 characters. `null` is refused.")
                JsonNode content,
        @Schema(implementation = Long[].class, description = "The whole list of account IDs the comment mentions "
                + "from now on, up to 10 counting the whole list. Only an account new to the list is checked by "
                + "the rules of `createComment`; one the comment mentions already is not checked again and may "
                + "stay. `null` is the same as an empty list. Left out, the mentions stay (S4-AC-24).")
                JsonNode mentions) {}
