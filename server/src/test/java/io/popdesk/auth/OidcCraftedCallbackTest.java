package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.CraftedOidcProvider.Endpoint;
import io.popdesk.support.Fixtures.Session;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The callback against a provider whose answers the test sets: a sign-in request is used once even when the token
 * endpoint would give a right token again (S11-AC-09), and the token endpoint failing (S11-AC-13).
 */
class OidcCraftedCallbackTest extends OidcCraftedAppTest {

    @Test
    @DisplayName("S11-AC-09 after a sign-in, the same code and state with the same cookie in another browser go back "
            + "to /login?oidc=failed without a session and without a token request, though the token endpoint would "
            + "give the same right token again")
    void usedSignInRequestIsGone() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();
        Started started = started(browser);
        String cookie = AuthChecks.cookieValue(started.response().setCookie(OidcFlow.COOKIE));
        String code = provider.give(provider.rs256(claims(started, OidcFlow.newSub(), agent.email())));
        Map<String, String> query = Map.of("code", code, "state", started.state());
        OidcFlow.assertRedirect(browser, oidc.callback(browser, query), "/");
        assertThat(provider.tokenRequests(code)).as("token requests of the first callback").hasSize(1);
        ApiClient again = console();
        again.setCookie(OidcFlow.COOKIE, cookie);

        ApiResponse reused = oidc.callback(again, query);

        OidcFlow.assertBackAtLogin(again, reused, "failed");
        OidcFlow.assertNoSession(again, CONSOLE);
        assertThat(provider.tokenRequests(code)).as("token requests after the code and state came again").hasSize(1);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"500, unavailable", "503, unavailable", "429, unavailable", "400, failed"})
    @DisplayName("S11-AC-13 the token endpoint answering 5xx or 429 goes back to /login?oidc=unavailable and 400 to "
            + "/login?oidc=failed, without a session, and the sign-in request is gone after")
    void tokenEndpointThatFails(int status, String value) {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();
        Started started = started(browser);
        String cookie = AuthChecks.cookieValue(started.response().setCookie(OidcFlow.COOKIE));
        String token = provider.rs256(claims(started, OidcFlow.newSub(), agent.email()));
        int before = provider.requests(Endpoint.TOKEN);
        provider.tokenAnswers(status);

        ApiResponse back = back(browser, started, token);

        provider.reset();
        OidcFlow.assertBackAtLogin(browser, back, value);
        OidcFlow.assertNoSession(browser, CONSOLE);
        assertThat(provider.requests(Endpoint.TOKEN) - before).as("token requests of the callback").isPositive();
        browser.setCookie(OidcFlow.COOKIE, cookie);
        OidcFlow.assertBackAtLogin(browser, back(browser, started, token), "failed");
        OidcFlow.assertNoSession(browser, CONSOLE);
    }
}
