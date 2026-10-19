package io.popdesk.helpdesk.account;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The claims of an ID token whose signature is already checked (S11-AC-10, S11-AC-16): pure, the claims and the
 * time in, a pass or the reason out. The signature, the algorithm, and the keys are the decoder's
 * ({@code OidcProvider}).
 */
final class IdTokenClaims {

    /** How far the provider's clock may be from the app's (S11-AC-10). */
    static final Duration SKEW = Duration.ofSeconds(60);

    /** Why the claims fail. The names go into the log, nothing of the token. */
    enum Failure {
        /** {@code iss} is missing or not the issuer. */
        ISSUER,
        /** {@code sub} is missing or empty. */
        SUBJECT,
        /** {@code aud} is missing or does not hold the client ID. */
        AUDIENCE,
        /** {@code azp} is there and not the client ID, or {@code aud} holds several values and there is no azp. */
        AUTHORIZED_PARTY,
        /** {@code exp} is missing, or passed more than {@link #SKEW} ago. */
        EXPIRED,
        /** {@code iat} is missing, or more than {@link #SKEW} ahead; or {@code nbf} is more than SKEW ahead. */
        NOT_YET_VALID,
        /** {@code nonce} is missing or not the sign-in request's. */
        NONCE
    }

    /**
     * What the claims must say: the issuer as configured ({@code OIDC_ISSUER}, compared as it is), the client ID,
     * and the SHA-256 of the sign-in request's nonce as {@link Tokens#hash} writes it.
     */
    record Expected(String issuer, String clientId, String nonceHash) {}

    private IdTokenClaims() {}

    /**
     * Checks the claims, in this order: {@code iss}, {@code sub}, {@code aud}, {@code azp}, {@code exp},
     * {@code iat} and {@code nbf}, {@code nonce}. Times may be {@link Instant}s or numbers of seconds since the
     * epoch; {@code aud} a text or a collection of texts; {@code iss} a text or a {@link URL}.
     *
     * <ul>
     *   <li>{@code exp} passes while {@code now} is at most 60 seconds after it: a token expired 60 seconds ago
     *       passes, 61 seconds ago fails.</li>
     *   <li>{@code iat} and {@code nbf} pass up to and including 60 seconds ahead of {@code now}; {@code nbf} may be
     *       missing, {@code iat} may not.</li>
     *   <li>{@code azp}, when present, must be the client ID; with several audiences it must be present.</li>
     * </ul>
     *
     * @param claims the claims of the token; a missing claim is a missing key or a null value
     * @param expected what they must say; none of its values is null
     * @param now the app's time ({@code Clock})
     * @return empty when every check passes; otherwise the first that fails
     */
    static Optional<Failure> check(Map<String, Object> claims, Expected expected, Instant now) {
        Object iss = claims.get("iss");
        String issuer = iss instanceof URL url ? url.toExternalForm() : iss instanceof String s ? s : null;
        if (!expected.issuer().equals(issuer)) {
            return Optional.of(Failure.ISSUER);
        }
        if (!(claims.get("sub") instanceof String sub) || sub.isEmpty()) {
            return Optional.of(Failure.SUBJECT);
        }
        List<String> audiences = audiences(claims.get("aud"));
        if (audiences == null || !audiences.contains(expected.clientId())) {
            return Optional.of(Failure.AUDIENCE);
        }
        Object azp = claims.get("azp");
        if (azp != null ? !expected.clientId().equals(azp) : audiences.size() > 1) {
            return Optional.of(Failure.AUTHORIZED_PARTY);
        }
        Instant expires = instant(claims.get("exp"));
        if (expires == null || now.isAfter(expires.plus(SKEW))) {
            return Optional.of(Failure.EXPIRED);
        }
        Instant latest = now.plus(SKEW);
        Instant issued = instant(claims.get("iat"));
        if (issued == null || issued.isAfter(latest)) {
            return Optional.of(Failure.NOT_YET_VALID);
        }
        Object nbf = claims.get("nbf");
        if (nbf != null) {
            Instant notBefore = instant(nbf);
            if (notBefore == null || notBefore.isAfter(latest)) {
                return Optional.of(Failure.NOT_YET_VALID);
            }
        }
        if (!(claims.get("nonce") instanceof String nonce) || !expected.nonceHash().equals(Tokens.hash(nonce))) {
            return Optional.of(Failure.NONCE);
        }
        return Optional.empty();
    }

    /**
     * The email to link an account by the first time (S11-AC-16): {@code email} with the spaces around it cut, when
     * {@code email_verified} is the JSON value {@code true} (not the text {@code "true"}) and the email is not empty
     * and is ASCII only, without a control character.
     *
     * @param claims the claims of a checked token
     * @return the email, or empty when the token links nothing by email
     */
    static Optional<String> verifiedEmail(Map<String, Object> claims) {
        if (!Boolean.TRUE.equals(claims.get("email_verified"))) {
            return Optional.empty();
        }
        if (!(claims.get("email") instanceof String written)) {
            return Optional.empty();
        }
        String email = written.strip();
        if (email.isEmpty() || email.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            return Optional.empty();
        }
        return Optional.of(email);
    }

    /**
     * Whether two emails are the same, ignoring the case of ASCII letters only: {@code K} and {@code k} are the same,
     * the Kelvin sign and {@code k} are not (S11-AC-16).
     *
     * @param a an email, or null
     * @param b an email, or null
     * @return false when either is null
     */
    static boolean sameEmail(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y && (x > 0x7f || y > 0x7f || asciiLower(x) != asciiLower(y))) {
                return false;
            }
        }
        return true;
    }

    private static char asciiLower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static List<String> audiences(Object aud) {
        if (aud instanceof String one) {
            return List.of(one);
        }
        if (aud instanceof Collection<?> many && !many.isEmpty()
                && many.stream().allMatch(String.class::isInstance)) {
            return many.stream().map(String.class::cast).toList();
        }
        return null;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number seconds) {
            return Instant.ofEpochSecond(seconds.longValue());
        }
        return null;
    }
}
