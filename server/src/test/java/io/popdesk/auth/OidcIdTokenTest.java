package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.OidcProvider;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The checks of the ID token (S11-AC-10) that the test provider can break: its claims are set by the login
 * ({@link OidcProvider#logIn}), so a token can come from another issuer, for another client, with another
 * {@code azp}, with several audiences and no {@code azp}, with another or no {@code nonce}, expired, or issued in the
 * future. The signature cannot be broken this way: the provider signs every token of {@code /default} with its own
 * RS256 key (an unknown key, {@code none}, and HS256 are left to the unit tests of the claim checks, design.md 3절).
 * Times within 60 seconds of the app's clock pass.
 */
class OidcIdTokenTest extends OidcAppTest {

    private static final String ANOTHER_CLIENT = "another-client";

    /** A change to the claims: the claims and the app's time in epoch seconds. */
    private interface Change extends BiConsumer<Map<String, Object>, Long> {}

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("iss of another issuer", (Change) (c, now) -> c.put("iss",
                        OidcProvider.shared().issuer("other"))),
                Arguments.of("aud of another client", (Change) (c, now) -> {
                    c.put("aud", ANOTHER_CLIENT);
                    c.put("azp", null);
                }),
                Arguments.of("azp of another client", (Change) (c, now) -> c.put("azp", ANOTHER_CLIENT)),
                Arguments.of("two audiences and no azp", (Change) (c, now) -> {
                    c.put("aud", List.of(OidcProvider.CLIENT_ID, ANOTHER_CLIENT));
                    c.put("azp", null);
                }),
                Arguments.of("another nonce", (Change) (c, now) -> c.put("nonce", PasswordSteps.tokenNeverIssued())),
                Arguments.of("no nonce", (Change) (c, now) -> c.put("nonce", null)),
                Arguments.of("exp 61 seconds ago", (Change) (c, now) -> {
                    c.put("iat", now - 3600);
                    c.put("nbf", now - 3600);
                    c.put("exp", now - 61);
                }),
                Arguments.of("iat 120 seconds ahead", (Change) (c, now) -> c.put("iat", now + 120)));
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of("exp 30 seconds ago", (Change) (c, now) -> {
                    c.put("iat", now - 3600);
                    c.put("nbf", now - 3600);
                    c.put("exp", now - 30);
                }),
                Arguments.of("iat 30 seconds ahead", (Change) (c, now) -> c.put("iat", now + 30)),
                Arguments.of("two audiences with azp of this client", (Change) (c, now) -> {
                    c.put("aud", List.of(OidcProvider.CLIENT_ID, ANOTHER_CLIENT));
                    c.put("azp", OidcProvider.CLIENT_ID);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    @DisplayName("S11-AC-10 an ID token of another issuer, for another client, with another azp, with two audiences "
            + "and no azp, with another or no nonce, expired over 60 seconds ago, or issued over 60 seconds ahead "
            + "goes back to /login?oidc=failed without a session")
    void tokenThatFailsItsChecksFails(String what, Change change) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();

        ApiResponse back = oidc.signIn(browser, OidcFlow.newSub(), changed(agent.email(), change));

        OidcFlow.assertBackAtLogin(browser, back, "failed");
        OidcFlow.assertNoSession(browser, "/api/console");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    @DisplayName("S11-AC-10 an ID token expired 30 seconds ago, issued 30 seconds ahead, or with two audiences and "
            + "the azp of this client signs in")
    void tokenWithinTheChecksSignsIn(String what, Change change) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();

        ApiResponse back = oidc.signIn(browser, OidcFlow.newSub(), changed(agent.email(), change));

        OidcFlow.assertRedirect(browser, back, "/");
        assertThat(browser.get("/api/console/me").expect(200).jsonLong("id")).as("account signed in")
                .isEqualTo(agent.accountId());
    }

    private Map<String, Object> changed(String email, Change change) {
        Map<String, Object> claims = oidc.claims(email);
        change.accept(claims, clock.instant().getEpochSecond());
        return claims;
    }
}
