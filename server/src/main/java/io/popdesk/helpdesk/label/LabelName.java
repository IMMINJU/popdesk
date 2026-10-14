package io.popdesk.helpdesk.label;

import io.popdesk.ApiError;
import io.popdesk.TextInput;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * The name of a label as it is stored (LBL-1, S6A-AC-02, S6A-AC-03): {@code name} as shown, and
 * {@code key}, which the unique key of the company's labels compares so that two names that differ only
 * in case are one name.
 *
 * @param name 1 to {@link #MAX} characters (code points), in Unicode NFC, without whitespace around it
 * @param key {@link #key} of {@code name}
 */
public record LabelName(String name, String key) {

    /** The longest name, in characters (code points) after it is trimmed and put in NFC. */
    public static final int MAX = 30;

    /**
     * The name of a request field as it is stored. Pure; adds at most one entry to {@code errors}.
     *
     * @param field the request field, for the entry in {@code errors}
     * @param received the value as sent; {@code null} when it was left out or sent as {@code null}
     * @return the name put in NFC with the whitespace around it removed ({@link String#strip()}), and its
     *     key, when that is 1 to {@link #MAX} code points: {@code "  Trim me  "} is {@code "Trim me"}, and
     *     60 code points that are 30 in NFC are taken. {@code null} when it is not, with one entry for
     *     {@code field} in {@code errors}: {@code REQUIRED} for {@code null} and the empty text,
     *     {@code BLANK} for whitespace only, {@code TOO_LONG} for over {@link #MAX} code points after
     *     trimming (31 is too long, also with spaces around it), {@code INVALID_FORMAT} for text with a
     *     NUL or an unpaired surrogate
     */
    public static LabelName of(String field, String received, List<ApiError.FieldError> errors) {
        String name = TextInput.required(field, received, MAX, errors);
        return name == null ? null : new LabelName(name, key(name));
    }

    /**
     * The key two names are compared by: the name in NFC, then in lower case by {@link Locale#ROOT}, so
     * the result does not depend on the server's language ({@code "İ"} is {@code "i̇"} and never the
     * Turkish dotless {@code "ı"}). Pure.
     *
     * @param name a name, not {@code null}; whitespace is kept as it is
     * @return the key; {@code "Billing"} and {@code "BILLING"} have the same one, {@code "Billing "} does
     *     not
     */
    public static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
