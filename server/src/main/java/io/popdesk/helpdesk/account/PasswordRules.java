package io.popdesk.helpdesk.account;

import java.util.Locale;
import java.util.Optional;

/**
 * The password rules of AUTH-5: 10 to 72 characters (code points, not UTF-16 units), not only
 * whitespace, and not the account's email. The codes are the {@code fields[].code} values of a 400
 * (conventions).
 */
public final class PasswordRules {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 72;

    public static final String BLANK = "BLANK";
    public static final String TOO_SHORT = "TOO_SHORT";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String SAME_AS_EMAIL = "SAME_AS_EMAIL";

    private PasswordRules() {}

    /** The rule the password breaks, or empty when it keeps them all. */
    public static Optional<String> broken(String password, String email) {
        if (password == null || password.isBlank()) {
            return Optional.of(BLANK);
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            return Optional.of(TOO_SHORT);
        }
        if (length > MAX_LENGTH) {
            return Optional.of(TOO_LONG);
        }
        if (email != null && password.strip().toLowerCase(Locale.ROOT).equals(email.strip().toLowerCase(Locale.ROOT))) {
            return Optional.of(SAME_AS_EMAIL);
        }
        return Optional.empty();
    }
}
