package io.popdesk;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The body of every error response (docs/integrate/conventions.md "Error response", ADR D-44).
 * {@code fields} is present only when the error is about specific fields.
 */
@Schema(description = "Error body. Clients choose the text they show by `code`; `message` is a default text "
        + "for a `code` they do not know.")
public record ApiError(
        @Schema(description = "Stable error key. See docs/integrate/conventions.md for the list.") String code,
        String message,
        @Schema(description = "Present only when the error is about specific fields (400 `INVALID_INPUT`).")
                List<FieldError> fields) {

    public record FieldError(
            @Schema(description = "Name of the request field or query parameter.") String field,
            @Schema(description = "Stable field error key, for example `TOO_LONG`.") String code) {}
}
