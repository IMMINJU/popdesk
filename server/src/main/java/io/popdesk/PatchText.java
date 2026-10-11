package io.popdesk;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A text field of a PATCH body that cannot be cleared (ADR D-44): left out, it keeps its value; sent as
 * {@code null}, it is 400 with field code {@code REQUIRED}, like an empty value.
 *
 * @param sent whether the request has the field
 * @param value what was sent; null when the field was left out or sent as {@code null}
 */
public record PatchText(boolean sent, String value) {

    /**
     * Reads a field bound as a {@link JsonNode}, which is null only when the request left the field out.
     * A number or a boolean is read as its text, as for a field bound as a string; a list or an object is
     * 400 {@code INVALID_FORMAT}.
     */
    public static PatchText of(String field, JsonNode node) {
        if (node == null) {
            return new PatchText(false, null);
        }
        if (node.isNull()) {
            return new PatchText(true, null);
        }
        if (!node.isValueNode()) {
            throw ApiException.invalid(field, "INVALID_FORMAT");
        }
        return new PatchText(true, node.asString());
    }

    /**
     * The cleaned value by {@link TextInput#required}; null when the field was left out, or when it is
     * invalid (and {@code errors} has its entry).
     */
    public String required(String field, int max, List<ApiError.FieldError> errors) {
        return sent ? TextInput.required(field, value, max, errors) : null;
    }
}
