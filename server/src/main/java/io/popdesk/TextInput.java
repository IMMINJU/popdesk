package io.popdesk;

import java.text.Normalizer;
import java.util.List;

/**
 * Text input rules (conventions "Text input"): NFC, one-line values trimmed, lengths in code points,
 * and no character that cannot be stored or shown (S1A-AC-67). The checks add a {@code fields[]} entry to a list instead of throwing, so a 400 lists every
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
     * Whether the text can be stored and shown: it has no NUL (U+0000), which PostgreSQL does not store,
     * and no surrogate without its pair, which is no character and has no UTF-8 form. A high surrogate
     * followed by a low one is one character outside the basic plane and is fine.
     */
    public static boolean storable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks a required one-line value of 1 to {@code max} code points, given as received; one that is
     * not {@link #storable} is {@code INVALID_FORMAT}.
     * Returns the cleaned value, or null when it is invalid (and {@code errors} has its entry).
     */
    public static String required(String field, String received, int max, List<ApiError.FieldError> errors) {
        if (received == null || received.isEmpty()) {
            errors.add(new ApiError.FieldError(field, "REQUIRED"));
            return null;
        }
        if (!storable(received)) {
            errors.add(new ApiError.FieldError(field, "INVALID_FORMAT"));
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

    /**
     * Checks multi-line text of up to {@code max} code points, given as received: NFC, not trimmed; text
     * that is not {@link #storable} is {@code INVALID_FORMAT}.
     * When {@code required}, an empty value is {@code REQUIRED} and one of only whitespace {@code BLANK};
     * otherwise a missing value is the empty text. Returns the cleaned value, or null when it is invalid
     * (and {@code errors} has its entry).
     */
    public static String multiLine(String field, String received, boolean required, int max,
            List<ApiError.FieldError> errors) {
        if (received == null || received.isEmpty()) {
            if (required) {
                errors.add(new ApiError.FieldError(field, "REQUIRED"));
                return null;
            }
            return "";
        }
        if (!storable(received)) {
            errors.add(new ApiError.FieldError(field, "INVALID_FORMAT"));
            return null;
        }
        String value = Normalizer.normalize(received, Normalizer.Form.NFC);
        if (required && value.isBlank()) {
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
