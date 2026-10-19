package io.popdesk.helpdesk.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.account.IdTokenClaims.Expected;
import io.popdesk.helpdesk.account.IdTokenClaims.Failure;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The claims of a signed ID token (S11-AC-10; conventions.md "OIDC sign-in"): {@code iss} equal to
 * {@code OIDC_ISSUER} as written, {@code aud} holding the client ID, {@code azp} equal to it when present and present
 * when {@code aud} has several values, {@code exp} not passed and {@code iat} not ahead, each by more than 60 seconds,
 * and the sign-in request's {@code nonce}. The email to link by the first time (S11-AC-16, S11-AC-17): only with
 * {@code email_verified} the JSON value {@code true} and an ASCII email, spaces around it cut, compared ignoring
 * the case of ASCII letters only.
 */
class IdTokenClaimsTest {

    private static final String ISSUER = "https://id.example/realms/popdesk";
    private static final String CLIENT_ID = "popdesk-client";
    private static final String NONCE = Tokens.create();
    private static final Expected EXPECTED = new Expected(ISSUER, CLIENT_ID, Tokens.hash(NONCE));
    private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", "248289761001");
        claims.put("aud", CLIENT_ID);
        claims.put("exp", NOW.plusSeconds(300));
        claims.put("iat", NOW.minusSeconds(5));
        claims.put("nonce", NONCE);
        return claims;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> claims = claims();
        claims.put(key, value);
        return claims;
    }

    private static Map<String, Object> without(String key) {
        Map<String, Object> claims = claims();
        claims.remove(key);
        return claims;
    }

    @Test
    @DisplayName("S11-AC-10 a token that keeps every rule passes")
    void passes() {
        assertThat(IdTokenClaims.check(claims(), EXPECTED, NOW)).isEmpty();
    }

    static Stream<Arguments> issuers() {
        return Stream.of(
                Arguments.of("another issuer", "https://other.example/realms/popdesk"),
                Arguments.of("one / more than configured", ISSUER + "/"),
                Arguments.of("http instead of https", "http://id.example/realms/popdesk"),
                Arguments.of("other letter case", "https://ID.example/realms/popdesk"),
                Arguments.of("an empty issuer", ""),
                Arguments.of("not a text", 42));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("issuers")
    @DisplayName("S11-AC-10 iss must be the configured issuer as written")
    void wrongIssuer(String what, Object iss) {
        assertThat(IdTokenClaims.check(with("iss", iss), EXPECTED, NOW)).contains(Failure.ISSUER);
    }

    @Test
    @DisplayName("S11-AC-10 a token without iss fails")
    void noIssuer() {
        assertThat(IdTokenClaims.check(without("iss"), EXPECTED, NOW)).contains(Failure.ISSUER);
    }

    @Test
    @DisplayName("S11-AC-10 the issuer configured with a last / wants it in iss too")
    void issuerWithSlash() {
        Expected slash = new Expected(ISSUER + "/", CLIENT_ID, Tokens.hash(NONCE));

        assertThat(IdTokenClaims.check(with("iss", ISSUER + "/"), slash, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(claims(), slash, NOW)).contains(Failure.ISSUER);
    }

    @Test
    @DisplayName("S11-AC-10 iss read by the decoder as a URL is compared as written")
    void issuerAsUrl() throws Exception {
        URL same = URI.create(ISSUER).toURL();
        URL other = URI.create("https://other.example/realms/popdesk").toURL();

        assertThat(IdTokenClaims.check(with("iss", same), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("iss", other), EXPECTED, NOW)).contains(Failure.ISSUER);
    }

    @Test
    @DisplayName("S11-AC-15 a token without sub, or with an empty one, fails")
    void subject() {
        assertThat(IdTokenClaims.check(without("sub"), EXPECTED, NOW)).contains(Failure.SUBJECT);
        assertThat(IdTokenClaims.check(with("sub", ""), EXPECTED, NOW)).contains(Failure.SUBJECT);
        assertThat(IdTokenClaims.check(with("sub", 7), EXPECTED, NOW)).contains(Failure.SUBJECT);
    }

    @Test
    @DisplayName("S11-AC-10 aud as one text must be the client ID")
    void audienceText() {
        assertThat(IdTokenClaims.check(with("aud", "other-client"), EXPECTED, NOW)).contains(Failure.AUDIENCE);
        assertThat(IdTokenClaims.check(with("aud", "POPDESK-CLIENT"), EXPECTED, NOW)).contains(Failure.AUDIENCE);
    }

    @Test
    @DisplayName("S11-AC-10 aud as a list of one value: the client ID passes without azp")
    void audienceListOfOne() {
        assertThat(IdTokenClaims.check(with("aud", List.of(CLIENT_ID)), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("aud", List.of("other")), EXPECTED, NOW)).contains(Failure.AUDIENCE);
    }

    @Test
    @DisplayName("S11-AC-10 aud missing, empty, or not texts fails")
    void audienceMissing() {
        assertThat(IdTokenClaims.check(without("aud"), EXPECTED, NOW)).contains(Failure.AUDIENCE);
        assertThat(IdTokenClaims.check(with("aud", List.of()), EXPECTED, NOW)).contains(Failure.AUDIENCE);
        assertThat(IdTokenClaims.check(with("aud", 5), EXPECTED, NOW)).contains(Failure.AUDIENCE);
    }

    @Test
    @DisplayName("S11-AC-10 several audiences holding the client ID pass with azp the client ID")
    void severalAudiencesWithAzp() {
        Map<String, Object> claims = with("aud", List.of("other", CLIENT_ID));
        claims.put("azp", CLIENT_ID);

        assertThat(IdTokenClaims.check(claims, EXPECTED, NOW)).isEmpty();
    }

    @Test
    @DisplayName("S11-AC-10 several audiences without azp fail")
    void severalAudiencesWithoutAzp() {
        assertThat(IdTokenClaims.check(with("aud", List.of(CLIENT_ID, "other")), EXPECTED, NOW))
                .contains(Failure.AUTHORIZED_PARTY);
        assertThat(IdTokenClaims.check(with("aud", Set.of(CLIENT_ID, "other")), EXPECTED, NOW))
                .contains(Failure.AUTHORIZED_PARTY);
    }

    @Test
    @DisplayName("S11-AC-10 several audiences with azp another client fail")
    void severalAudiencesOtherAzp() {
        Map<String, Object> claims = with("aud", List.of("other", CLIENT_ID));
        claims.put("azp", "other");

        assertThat(IdTokenClaims.check(claims, EXPECTED, NOW)).contains(Failure.AUTHORIZED_PARTY);
    }

    @Test
    @DisplayName("S11-AC-10 several audiences without the client ID fail on aud, azp or not")
    void severalAudiencesWithoutClient() {
        Map<String, Object> claims = with("aud", List.of("a", "b"));
        claims.put("azp", CLIENT_ID);

        assertThat(IdTokenClaims.check(claims, EXPECTED, NOW)).contains(Failure.AUDIENCE);
    }

    @Test
    @DisplayName("S11-AC-10 azp present with one audience must be the client ID")
    void azpWithOneAudience() {
        assertThat(IdTokenClaims.check(with("azp", CLIENT_ID), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("azp", "other"), EXPECTED, NOW)).contains(Failure.AUTHORIZED_PARTY);
        assertThat(IdTokenClaims.check(with("azp", ""), EXPECTED, NOW)).contains(Failure.AUTHORIZED_PARTY);
    }

    @Test
    @DisplayName("S11-AC-10 exp passed 60 seconds ago passes, 61 seconds ago fails")
    void expiresAtSkew() {
        assertThat(IdTokenClaims.check(with("exp", NOW.minusSeconds(60)), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("exp", NOW.minusSeconds(61)), EXPECTED, NOW))
                .contains(Failure.EXPIRED);
    }

    @Test
    @DisplayName("S11-AC-10 exp passed 60 seconds and one millisecond ago fails")
    void expiresJustPastSkew() {
        assertThat(IdTokenClaims.check(with("exp", NOW.minus(Duration.ofMillis(60_001))), EXPECTED, NOW))
                .contains(Failure.EXPIRED);
    }

    @Test
    @DisplayName("S11-AC-10 exp as seconds since the epoch: the same 60 seconds")
    void expiresAsSeconds() {
        long now = NOW.getEpochSecond();

        assertThat(IdTokenClaims.check(with("exp", now - 60), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("exp", now - 61), EXPECTED, NOW)).contains(Failure.EXPIRED);
        assertThat(IdTokenClaims.check(with("exp", (int) (now + 300)), EXPECTED, NOW)).isEmpty();
    }

    @Test
    @DisplayName("S11-AC-10 a token without exp, or with exp not a time, fails")
    void noExpiry() {
        assertThat(IdTokenClaims.check(without("exp"), EXPECTED, NOW)).contains(Failure.EXPIRED);
        assertThat(IdTokenClaims.check(with("exp", "2099-01-01T00:00:00Z"), EXPECTED, NOW))
                .contains(Failure.EXPIRED);
    }

    @Test
    @DisplayName("S11-AC-10 iat 60 seconds ahead passes, 61 seconds ahead fails")
    void issuedAtSkew() {
        assertThat(IdTokenClaims.check(with("iat", NOW.plusSeconds(60)), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("iat", NOW.plusSeconds(61)), EXPECTED, NOW))
                .contains(Failure.NOT_YET_VALID);
        assertThat(IdTokenClaims.check(with("iat", NOW.plus(Duration.ofMillis(60_001))), EXPECTED, NOW))
                .contains(Failure.NOT_YET_VALID);
    }

    @Test
    @DisplayName("S11-AC-10 iat as seconds since the epoch, and in the past")
    void issuedAtAsSeconds() {
        long now = NOW.getEpochSecond();

        assertThat(IdTokenClaims.check(with("iat", now + 60), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("iat", now + 61), EXPECTED, NOW)).contains(Failure.NOT_YET_VALID);
        assertThat(IdTokenClaims.check(with("iat", now - 3600), EXPECTED, NOW)).isEmpty();
    }

    @Test
    @DisplayName("S11-AC-10 a token without iat fails")
    void noIssuedAt() {
        assertThat(IdTokenClaims.check(without("iat"), EXPECTED, NOW)).contains(Failure.NOT_YET_VALID);
    }

    @Test
    @DisplayName("S11-AC-10 nbf 60 seconds ahead passes, 61 seconds ahead fails, a missing nbf passes")
    void notBeforeSkew() {
        assertThat(IdTokenClaims.check(with("nbf", NOW.plusSeconds(60)), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("nbf", NOW.minusSeconds(60)), EXPECTED, NOW)).isEmpty();
        assertThat(IdTokenClaims.check(with("nbf", NOW.plusSeconds(61)), EXPECTED, NOW))
                .contains(Failure.NOT_YET_VALID);
        assertThat(IdTokenClaims.check(with("nbf", NOW.getEpochSecond() + 61), EXPECTED, NOW))
                .contains(Failure.NOT_YET_VALID);
        assertThat(IdTokenClaims.check(with("nbf", "soon"), EXPECTED, NOW)).contains(Failure.NOT_YET_VALID);
    }

    @Test
    @DisplayName("S11-AC-10 the nonce must be the sign-in request's")
    void nonce() {
        assertThat(IdTokenClaims.check(with("nonce", Tokens.create()), EXPECTED, NOW)).contains(Failure.NONCE);
        assertThat(IdTokenClaims.check(without("nonce"), EXPECTED, NOW)).contains(Failure.NONCE);
        assertThat(IdTokenClaims.check(with("nonce", ""), EXPECTED, NOW)).contains(Failure.NONCE);
        assertThat(IdTokenClaims.check(with("nonce", "not-a-token"), EXPECTED, NOW)).contains(Failure.NONCE);
        assertThat(IdTokenClaims.check(with("nonce", Tokens.hash(NONCE)), EXPECTED, NOW)).contains(Failure.NONCE);
    }

    @Test
    @DisplayName("S11-AC-10 with several rules broken the first in the order iss, sub, aud, azp, exp, iat, nonce "
            + "is given")
    void order() {
        Map<String, Object> all = new HashMap<>();
        all.put("iss", "https://other.example");
        all.put("aud", "other");
        all.put("exp", NOW.minusSeconds(3600));
        assertThat(IdTokenClaims.check(all, EXPECTED, NOW)).contains(Failure.ISSUER);

        Map<String, Object> late = claims();
        late.put("exp", NOW.minusSeconds(3600));
        late.put("iat", NOW.plusSeconds(3600));
        late.put("nonce", "x");
        assertThat(IdTokenClaims.check(late, EXPECTED, NOW)).contains(Failure.EXPIRED);
    }

    static Stream<Arguments> unverified() {
        return Stream.of(
                Arguments.of("email_verified false", false, "ann@example.com"),
                Arguments.of("email_verified missing", null, "ann@example.com"),
                Arguments.of("email_verified the text true", "true", "ann@example.com"),
                Arguments.of("email_verified the number 1", 1, "ann@example.com"),
                Arguments.of("no email", true, null),
                Arguments.of("an empty email", true, ""),
                Arguments.of("an email of spaces", true, "   "),
                Arguments.of("an email that is not a text", true, 42),
                Arguments.of("an email with a non-ASCII letter", true, "josé@example.com"),
                Arguments.of("an email with the Kelvin sign", true, "Kim@example.com"),
                Arguments.of("an email with a full-width letter", true, "ａnn@example.com"),
                Arguments.of("an email in Korean", true, "민주@example.com"),
                Arguments.of("an email with NUL", true, "ann\u0000@example.com"),
                Arguments.of("an email with a line feed inside", true, "ann\n@example.com"),
                Arguments.of("an email with DEL", true, "ann\u007F@example.com"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unverified")
    @DisplayName("S11-AC-16 S11-AC-17 without email_verified true and an ASCII email, nothing links by email")
    void notVerified(String what, Object verified, Object email) {
        Map<String, Object> claims = claims();
        claims.put("email_verified", verified);
        claims.put("email", email);

        assertThat(IdTokenClaims.verifiedEmail(claims)).isEmpty();
    }

    @Test
    @DisplayName("S11-AC-16 email_verified true and an ASCII email give the email")
    void verified() {
        Map<String, Object> claims = with("email_verified", true);
        claims.put("email", "Ann.Lee+desk@Example.com");

        assertThat(IdTokenClaims.verifiedEmail(claims)).contains("Ann.Lee+desk@Example.com");
    }

    @Test
    @DisplayName("S11-AC-16 the spaces around the email are cut")
    void verifiedSpaces() {
        Map<String, Object> claims = with("email_verified", Boolean.TRUE);
        claims.put("email", "  ann@example.com\t");

        assertThat(IdTokenClaims.verifiedEmail(claims)).contains("ann@example.com");
    }

    @Test
    @DisplayName("S11-AC-16 every printable ASCII character but the space is taken")
    void printableAscii() {
        Map<String, Object> claims = with("email_verified", true);
        claims.put("email", "!~@example.com");

        assertThat(IdTokenClaims.verifiedEmail(claims)).contains("!~@example.com");
    }

    @Test
    @DisplayName("S11-AC-16 emails differing only in ASCII letter case are the same")
    void sameIgnoringAsciiCase() {
        assertThat(IdTokenClaims.sameEmail("Ann@Example.COM", "ann@example.com")).isTrue();
        assertThat(IdTokenClaims.sameEmail("ann@example.com", "ANN@EXAMPLE.COM")).isTrue();
        assertThat(IdTokenClaims.sameEmail("AZ@x", "az@x")).isTrue();
        assertThat(IdTokenClaims.sameEmail("az@x", "AZ@x")).isTrue();
        assertThat(IdTokenClaims.sameEmail("ann@example.com", "ann@example.com")).isTrue();
        assertThat(IdTokenClaims.sameEmail("", "")).isTrue();
    }

    @Test
    @DisplayName("S11-AC-16 other emails are not the same")
    void notSame() {
        assertThat(IdTokenClaims.sameEmail("ann@example.com", "bob@example.com")).isFalse();
        assertThat(IdTokenClaims.sameEmail("ann@example.com", "ann@example.co")).isFalse();
        assertThat(IdTokenClaims.sameEmail("ann@example.com", "ann@example.com ")).isFalse();
        assertThat(IdTokenClaims.sameEmail("a@x", "b@x")).isFalse();
    }

    @Test
    @DisplayName("S11-AC-16 only ASCII letters ignore case: [ and {, @ and `, the Kelvin sign and k are not the same")
    void onlyAsciiLetters() {
        assertThat(IdTokenClaims.sameEmail("[@x", "{@x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("{@x", "[@x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("a@x", "a`x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("Kim@x", "kim@x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("kim@x", "Kim@x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("Kim@x", "Kim@x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("josÉ@x", "josé@x")).isFalse();
        assertThat(IdTokenClaims.sameEmail("ſ@x", "s@x")).isFalse();
    }

    @Test
    @DisplayName("S11-AC-16 no email is the same as nothing")
    void nulls() {
        assertThat(IdTokenClaims.sameEmail(null, "ann@example.com")).isFalse();
        assertThat(IdTokenClaims.sameEmail("ann@example.com", null)).isFalse();
        assertThat(IdTokenClaims.sameEmail(null, null)).isFalse();
    }
}
