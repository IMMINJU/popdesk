package io.popdesk.helpdesk.account;

/**
 * The page an OIDC sign-in goes to after the callback (S11-AC-05): the {@code next} of the start, kept only when it
 * is a path of the same app. Anything else is taken as {@code /}, so a link with {@code next=//evil.example} cannot
 * send the person to another site.
 */
final class SafeNext {

    /** The longest {@code next} kept, in characters (code points). */
    static final int MAX_LENGTH = 512;

    static final String HOME = "/";

    private SafeNext() {}

    /**
     * {@code next} when it starts with one {@code /}, the character after it is neither {@code /} nor {@code \},
     * it has no control character (C0, DEL, or C1), and it is at most {@value #MAX_LENGTH} characters; otherwise
     * {@code /}.
     *
     * @param next the value as received; null and the empty text give {@code /}
     * @return {@code next} unchanged, or {@code /}; never null
     */
    static String of(String next) {
        if (next == null || next.isEmpty() || next.charAt(0) != '/') {
            return HOME;
        }
        if (next.length() > 1 && (next.charAt(1) == '/' || next.charAt(1) == '\\')) {
            return HOME;
        }
        if (next.codePointCount(0, next.length()) > MAX_LENGTH) {
            return HOME;
        }
        if (next.codePoints().anyMatch(Character::isISOControl)) {
            return HOME;
        }
        return next;
    }
}
