package io.popdesk;

import java.text.Normalizer;
import java.util.List;

/**
 * Text input rules (conventions "Text input"): NFC, one-line values trimmed, lengths in code points.
 * The checks add a {@code fields[]} entry to a list instead of throwing, so a 400 lists every
 * invalid field at once (D-44).
 */
public final class TextInput {

    private TextInput() {}

    /** NFC, then leading and trailing whitespace removed; null stays null. */
    public static String oneLine(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    public static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /**
     * Checks a required one-line value of {@code min} to {@code max} code points, given as received.
     * Returns the cleaned value, or null when it is invalid (and {@code errors} has its entry).
     */
    public static String required(String field, String received, int max, List<ApiError.FieldError> errors) {
        if (received == null || received.isEmpty()) {
            errors.add(new ApiError.FieldError(field, "REQUIRED"));
            return null;
        }
        String value = oneLine(received);
        if (value.isEmpty()) {
            errors.add(new ApiError.FieldError(field, "BLANK"));
            return null;
        }
        if (length(value) > max) {
            errors.add(new ApiError.FieldError(field, "TOO_LONG"));
            return null;
        }
        return value;
    }
}
