package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.OidcProvider.Redirect;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The callback of an OIDC sign-in: a new session for the matched account (S11-AC-09), the checks of the cookie and
 * the {@code state} before the token endpoint is called (S11-AC-11), the person cancelling at the provider
 * (S11-AC-12), and a code the provider never gave (S11-AC-13). A sign-in request is used once.
 *
 * <p>Whether the app called the token endpoint is seen at the provider: a code nobody exchanged is still there
 * ({@code OidcProvider#codeUnused}). The test provider answers a code it never gave with 200 and a token without a
 * nonce, so that case fails at the ID token, not at the token endpoint; the token endpoint answering 4xx, 5xx, or 429
 * is in {@link OidcCraftedCallbackTest}, and the second use of a sign-in request with a right token in
 * {@link OidcCraftedCallbackTest#usedSignInRequestIsGone}.
 */
class OidcCallbackTest extends OidcAppTest {

    /** Ways the callback does not belong to the sign-in its browser started (S11-AC-11). */
    enum Mismatch {
        NO_COOKIE,
        COOKIE_OF_ANOTHER_BROWSER,
        UNKNOWN_STATE,
        OVER_TEN_MINUTES,
        STARTED_ON_THE_PORTAL
    }

    @Test
    @DisplayName("S11-AC-09 the callback starts a session for the matched account with a new session ID and a new "
            + "CSRF token, answers 302 to /, and the same code and state with the same cookie in another browser "
            + "then go back to /login?oidc=failed without a session")
    void callbackStartsANewSession() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        String sessionCookie = agent.client().cookies().keySet().stream()
                .filter(name -> !name.equals(ApiClient.CSRF_COOKIE)).findFirst().orElseThrow();
        ApiClient browser = console();
        String csrfBefore = browser.csrfToken();
        String planted = "planted-" + OidcFlow.newSub();
        browser.setCookie(sessionCookie, planted);
        Started started = oidc.started(browser);
        String cookie = AuthChecks.cookieValue(started.response().setCookie(OidcFlow.COOKIE));
        Redirect atProvider = oidc.logIn(started, OidcFlow.newSub(), oidc.claims(agent.email()));

        ApiResponse back = oidc.callback(browser, atProvider);

        OidcFlow.assertRedirect(browser, back, "/");
        assertThat(back.header("Cache-Control")).as("Cache-Control of %s", back).contains("no-store");
        assertThat(back.setCookie(sessionCookie)).as("%s cookie set by %s", sessionCookie, back).isNotNull();
        assertThat(browser.cookie(sessionCookie)).as("session ID after the sign-in").isPresent().get()
                .isNotEqualTo(planted);
        assertThat(browser.get("/api/console/me").expect(200).jsonLong("id")).as("account of the session")
                .isEqualTo(agent.accountId());
        assertThat(browser.csrfToken()).as("CSRF token after the sign-in").isNotEqualTo(csrfBefore);
        ApiClient again = console();
        again.setCookie(OidcFlow.COOKIE, cookie);
        ApiResponse reused = oidc.callback(again, atProvider);
        OidcFlow.assertBackAtLogin(again, reused, "failed");
        assertThat(reused.header("Cache-Control")).as("Cache-Control of %s", reused).contains("no-store");
        OidcFlow.assertNoSession(again, "/api/console");
    }

    @ParameterizedTest
    @EnumSource(Mismatch.class)
    @DisplayName("S11-AC-11 without the cookie, with another browser's cookie, with a state the app never gave, after "
            + "10 minutes, or started on the portal and back on the console, the callback goes back to "
            + "/login?oidc=failed without a session and without exchanging the code")
    void callbackThatDoesNotMatchFails(Mismatch mismatch) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient starter = mismatch == Mismatch.STARTED_ON_THE_PORTAL ? portal() : console();
        Started started = oidc.started(starter);
        Redirect atProvider = oidc.logIn(started, OidcFlow.newSub(), oidc.claims(agent.email()));
        Map<String, String> query = new LinkedHashMap<>(atProvider.query());
        ApiClient browser = switch (mismatch) {
            case NO_COOKIE -> console();
            case COOKIE_OF_ANOTHER_BROWSER -> {
                ApiClient other = console();
                oidc.started(other);
                yield other;
            }
            case UNKNOWN_STATE -> {
                query.put("state", PasswordSteps.tokenNeverIssued());
                yield starter;
            }
            case OVER_TEN_MINUTES -> {
                clock.advance(Duration.ofMinutes(10).plusSeconds(1));
                yield starter;
            }
            case STARTED_ON_THE_PORTAL -> {
                ApiClient console = console();
                starter.cookie(OidcFlow.COOKIE).ifPresent(v -> console.setCookie(OidcFlow.COOKIE, v));
                yield console;
            }
        };

        ApiResponse back = oidc.callback(browser, query);

        OidcFlow.assertBackAtLogin(browser, back, "failed");
        OidcFlow.assertNoSession(browser, "/api/console");
        assertThat(oidc.provider().codeUnused(atProvider.code())).as("the code is still unused at the provider")
                .isTrue();
    }

    @Test
    @DisplayName("S11-AC-12 an error from the provider with the browser's state and cookie goes back to /login "
            + "without a query and without a session, and that sign-in request cannot be used after")
    void cancelGoesBackToTheSignInPage() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();
        Started started = oidc.started(browser);
        String cookie = AuthChecks.cookieValue(started.response().setCookie(OidcFlow.COOKIE));

        ApiResponse cancelled = oidc.callback(browser, Map.of("error", "access_denied", "state", started.state()));

        OidcFlow.assertRedirect(browser, cancelled, "/login");
        OidcFlow.assertNoSession(browser, "/api/console");
        Redirect atProvider = oidc.logIn(started, OidcFlow.newSub(), oidc.claims(agent.email()));
        browser.setCookie(OidcFlow.COOKIE, cookie);
        ApiResponse later = oidc.callback(browser, atProvider);
        OidcFlow.assertBackAtLogin(browser, later, "failed");
        OidcFlow.assertNoSession(browser, "/api/console");
    }

    @Test
    @DisplayName("S11-AC-12 an error from the provider with a state that does not match the browser's cookie goes "
            + "back to /login?oidc=failed, and the sign-in the browser started still works")
    void errorWithAnotherStateFails() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();
        Started mine = oidc.started(browser);
        String cookie = AuthChecks.cookieValue(mine.response().setCookie(OidcFlow.COOKIE));

        ApiResponse wrongState = oidc.callback(browser,
                Map.of("error", "access_denied", "state", PasswordSteps.tokenNeverIssued()));

        OidcFlow.assertBackAtLogin(browser, wrongState, "failed");
        OidcFlow.assertNoSession(browser, "/api/console");
        browser.setCookie(OidcFlow.COOKIE, cookie);
        ApiResponse back = oidc.callback(browser, oidc.logIn(mine, OidcFlow.newSub(), oidc.claims(agent.email())));
        OidcFlow.assertRedirect(browser, back, "/");
    }

    @Test
    @DisplayName("S11-AC-13 a code the provider never gave, with the browser's state and cookie, goes back to "
            + "/login?oidc=failed without a session, and the sign-in request is gone after")
    void codeTheProviderDoesNotTakeFails() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();
        Started started = oidc.started(browser);
        String cookie = AuthChecks.cookieValue(started.response().setCookie(OidcFlow.COOKIE));
        Redirect atProvider = oidc.logIn(started, OidcFlow.newSub(), oidc.claims(agent.email()));

        ApiResponse wrongCode = oidc.callback(browser,
                Map.of("code", "inv-code-" + OidcFlow.newSub(), "state", atProvider.state()));

        OidcFlow.assertBackAtLogin(browser, wrongCode, "failed");
        OidcFlow.assertNoSession(browser, "/api/console");
        browser.setCookie(OidcFlow.COOKIE, cookie);
        ApiResponse rightCodeAfter = oidc.callback(browser, atProvider);
        OidcFlow.assertBackAtLogin(browser, rightCodeAfter, "failed");
        OidcFlow.assertNoSession(browser, "/api/console");
    }
}
