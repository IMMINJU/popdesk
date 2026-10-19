package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.CraftedOidcProvider;
import io.popdesk.support.CraftedOidcProvider.Endpoint;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The checks of the ID token (S11-AC-10) with tokens the test writes: the signature (RS256 and ES256 by a key of the
 * JWKS pass; {@code alg: none}, HS256 with the client secret, and a key not in the JWKS fail; an unknown {@code kid}
 * makes the app fetch the JWKS once more) and the claims ({@code aud}, {@code azp}, {@code iss}, {@code nonce},
 * {@code exp}, {@code iat}, with 60 seconds of clock skew). Every token is otherwise right: the start's nonce, this
 * client, this issuer, the verified email of a console account.
 */
class OidcCraftedTokenTest extends OidcCraftedAppTest {

    private static final String ANOTHER_CLIENT = "another-client";

    /** A token of the claims, written by the provider. */
    private interface Token extends BiFunction<CraftedOidcProvider, Map<String, Object>, String> {}

    /**
     * RS256 by the key of the JWKS after {@code change} to the claims; the change also gets the app's time in epoch
     * seconds (the claims' {@code iat} before the change).
     */
    private static Token rs256(BiConsumer<Map<String, Object>, Long> change) {
        return (provider, claims) -> {
            change.accept(claims, ((Number) claims.get("iat")).longValue());
            return provider.rs256(claims);
        };
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("alg none", (Token) (p, c) -> CraftedOidcProvider.unsigned(c)),
                Arguments.of("HS256 with the client secret", (Token) CraftedOidcProvider::hs256),
                Arguments.of("RS256 by a key not in the JWKS under the kid of one that is", (Token) (p, c) ->
                        CraftedOidcProvider.signed(c, CraftedOidcProvider.rsaKey("stray"), p.rsa().getKeyID())),
                Arguments.of("RS256 by a key not in the JWKS under a kid nobody knows", (Token) (p, c) ->
                        CraftedOidcProvider.signed(c, CraftedOidcProvider.rsaKey("stray"), "stray-" + OidcFlow.newSub())),
                Arguments.of("two audiences and no azp", rs256((c, now) ->
                        c.put("aud", List.of(CraftedOidcProvider.CLIENT_ID, ANOTHER_CLIENT)))),
                Arguments.of("two audiences and the azp of another client", rs256((c, now) -> {
                    c.put("aud", List.of(CraftedOidcProvider.CLIENT_ID, ANOTHER_CLIENT));
                    c.put("azp", ANOTHER_CLIENT);
                })),
                Arguments.of("this audience and the azp of another client", rs256((c, now) ->
                        c.put("azp", ANOTHER_CLIENT))),
                Arguments.of("iss of another issuer", rs256((c, now) -> c.put("iss", c.get("iss") + "/other"))),
                Arguments.of("another nonce", rs256((c, now) -> c.put("nonce", PasswordSteps.tokenNeverIssued()))),
                Arguments.of("no nonce", rs256((c, now) -> c.remove("nonce"))),
                Arguments.of("exp 61 seconds ago", rs256((c, now) -> {
                    c.put("iat", now - 3600);
                    c.put("nbf", now - 3600);
                    c.put("exp", now - 61);
                })),
                Arguments.of("iat 61 seconds ahead", rs256((c, now) -> c.put("iat", now + 61))));
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of("RS256 by the RSA key of the JWKS", (Token) CraftedOidcProvider::rs256),
                Arguments.of("ES256 by the P-256 key of the JWKS", (Token) CraftedOidcProvider::es256),
                Arguments.of("exp 30 seconds ago", rs256((c, now) -> {
                    c.put("iat", now - 3600);
                    c.put("nbf", now - 3600);
                    c.put("exp", now - 30);
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    @DisplayName("S11-AC-10 an ID token with alg none, HS256 with the client secret, a key not in the JWKS, two "
            + "audiences without azp or with another azp, another azp, another iss, another or no nonce, expired over "
            + "60 seconds ago, or issued over 60 seconds ahead goes back to /login?oidc=failed without a session")
    void craftedTokenThatFailsItsChecksFails(String what, Token token) {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();
        Started started = started(browser);

        ApiResponse back = back(browser, started,
                token.apply(provider, claims(started, OidcFlow.newSub(), agent.email())));

        OidcFlow.assertBackAtLogin(browser, back, "failed");
        OidcFlow.assertNoSession(browser, CONSOLE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    @DisplayName("S11-AC-10 an ID token signed RS256 or ES256 by a key of the JWKS, or expired 30 seconds ago, signs "
            + "in")
    void craftedTokenWithinTheChecksSignsIn(String what, Token token) {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();
        Started started = started(browser);

        ApiResponse back = back(browser, started,
                token.apply(provider, claims(started, OidcFlow.newSub(), agent.email())));

        OidcFlow.assertRedirect(browser, back, "/");
        assertThat(browser.get(CONSOLE + "/me").expect(200).jsonLong("id")).as("account signed in")
                .isEqualTo(agent.accountId());
    }

    @Test
    @DisplayName("S11-AC-10 an ID token under a kid the app has not seen, signed by a key the JWKS has since, makes the "
            + "app fetch the JWKS exactly once more and signs in")
    void unknownKidFetchesTheKeysOnceMore() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        signedIn(fixtures.agent(admin));
        RSAKey added = CraftedOidcProvider.rsaKey("added-" + OidcFlow.newSub());
        provider.publish(added);
        ApiClient browser = console();
        Started started = started(browser);
        String token = CraftedOidcProvider.signed(claims(started, OidcFlow.newSub(), agent.email()), added,
                added.getKeyID());
        int before = provider.requests(Endpoint.JWKS);

        ApiResponse back = back(browser, started, token);

        OidcFlow.assertRedirect(browser, back, "/");
        assertThat(browser.get(CONSOLE + "/me").expect(200).jsonLong("id")).as("account signed in")
                .isEqualTo(agent.accountId());
        assertThat(provider.requests(Endpoint.JWKS) - before).as("JWKS requests during the callback").isEqualTo(1);
    }
}
