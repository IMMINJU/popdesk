package io.popdesk.helpdesk.account;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The tokens of invitation and password reset links: 32 random bytes, shown only in the link. The
 * database keeps the SHA-256 of the bytes, never the token (S0-AC-35, S1A-AC-08).
 */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** 32 random bytes, base64url without padding (43 characters). */
    static String create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The SHA-256 of a token's 32 bytes as lower-case hex, or null when the text is not a token. */
    static String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}=?")) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getUrlDecoder().decode(token)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JVM", e);
        }
    }
}
