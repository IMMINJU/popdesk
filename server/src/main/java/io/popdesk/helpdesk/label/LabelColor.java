package io.popdesk.helpdesk.label;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** The color of a label (LBL-1, S6A-AC-04): {@code #} and six hexadecimal digits, stored in capitals. */
public final class LabelColor {

    /** The gray of a label created without a color (S6A-AC-01). */
    public static final String DEFAULT = "#808080";

    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    private LabelColor() {}

    /**
     * The color as it is stored. Pure.
     *
     * @param received the value as sent; {@code null} when it was left out or sent as {@code null}
     * @return {@link #DEFAULT} for {@code null}; the value with its letters in capitals when it is exactly
     *     {@code #} and six digits {@code 0-9}, {@code a-f}, or {@code A-F} ({@code "#a1b2c3"} is
     *     {@code "#A1B2C3"}); empty for anything else: the empty text, no {@code #} ({@code "123456"}),
     *     five or seven digits, a letter after {@code f} ({@code "#GGGGGG"}), a name ({@code "red"}), or
     *     whitespace anywhere ({@code " #123456"}, {@code "#12 456"})
     */
    public static Optional<String> parse(String received) {
        if (received == null) {
            return Optional.of(DEFAULT);
        }
        return COLOR.matcher(received).matches() ? Optional.of(received.toUpperCase(Locale.ROOT)) : Optional.empty();
    }
}
