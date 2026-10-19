package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.OidcProvider;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The start of an OIDC sign-in with OIDC on for both apps: what the sign-in pages are told (S11-AC-03), the 302 to
 * the provider and the cookie (S11-AC-04), the limit per IP (S11-AC-08), and old sign-in requests going away when a
 * new one is made (S11-AC-14).
 */
class OidcStartTest extends OidcAppTest {

    /** The base64url of 32 bytes or more, without padding: a {@code state}, a {@code nonce}, a PKCE challenge. */
    private static final String RANDOM = "[A-Za-z0-9_-]{43,}";

    @Test
    @DisplayName("S11-AC-03 with OIDC on for both apps sign-in-options answers {oidc: true} on both hosts, with no "
            + "other field (no provider address, no client ID), and no-store")
    void signInOptionsSayOn() {
        for (ApiClient browser : List.of(portal(), console())) {
            ApiResponse options = browser.get(OidcFlow.OPTIONS).expect(200);
            JsonNode body = options.json();

            assertThat(OidcFlow.fieldNames(body)).as("fields of %s on %s", body, browser.host())
                    .isEqualTo(Set.of("oidc"));
            assertThat(body.path("oidc").isBoolean() && body.path("oidc").booleanValue())
                    .as("oidc is true in %s on %s", body, browser.host()).isTrue();
            assertThat(options.text()).as("body of %s", options).doesNotContain(OidcProvider.CLIENT_ID)
                    .doesNotContain(oidc.provider().origin());
            assertThat(options.header("Cache-Control")).as("Cache-Control of %s", options).contains("no-store");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {AppTest.PORTAL_HOST, AppTest.CONSOLE_HOST})
    @DisplayName("S11-AC-04 the start answers 302 to the provider's authorization endpoint with exactly the eight "
            + "query values, redirect_uri on the app's public address, S256, and sets POPDESK_OIDC HttpOnly, "
            + "SameSite=Lax, Path=/, host-only, not Secure on http, for 600 seconds, with no-store")
    void startGoesToTheProvider(String host) {
        ApiResponse start = oidc.start(client(host));
        Started started = oidc.started(start);
        Map<String, String> query = started.query();
        String authorize = oidc.provider().discovery().path("authorization_endpoint").asString();

        assertThat(started.location().toString()).as("Location of %s", start).startsWith(authorize + "?");
        assertThat(query.keySet()).as("query of %s", started.location()).isEqualTo(OidcFlow.AUTHORIZATION_QUERY);
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("client_id")).isEqualTo(OidcProvider.CLIENT_ID);
        assertThat(query.get("redirect_uri")).isEqualTo("http://" + host + OidcFlow.CALLBACK);
        assertThat(query.get("scope")).isEqualTo("openid email profile");
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query.get("code_challenge")).as("code_challenge").matches(RANDOM);
        assertThat(query.get("state")).as("state").matches(RANDOM);
        assertThat(query.get("nonce")).as("nonce").matches(RANDOM).isNotEqualTo(query.get("state"));

        String cookie = start.setCookie(OidcFlow.COOKIE);
        Map<String, String> attributes = AuthChecks.cookieAttributes(cookie);
        assertThat(AuthChecks.cookieValue(cookie)).as("value of %s", cookie).isNotBlank()
                .isNotEqualTo(query.get("state")).isNotEqualTo(query.get("nonce"));
        assertThat(attributes).as(cookie).containsKey("httponly").doesNotContainKey("secure")
                .doesNotContainKey("domain");
        assertThat(attributes.get("samesite")).as(cookie).isEqualToIgnoringCase("Lax");
        assertThat(attributes.get("path")).as(cookie).isEqualTo("/");
        assertThat(attributes.get("max-age")).as(cookie).isEqualTo("600");
        assertThat(start.headers("Set-Cookie")).as("cookies set by %s", start)
                .noneMatch(c -> c.startsWith("__Host-" + OidcFlow.COOKIE + "="));
        assertThat(start.header("Cache-Control")).as("Cache-Control of %s", start).contains("no-store");
    }

    @Test
    @DisplayName("S11-AC-04 two starts in the same browser get a different state, nonce, PKCE challenge, and cookie")
    void everyStartIsNew() {
        ApiClient browser = console();
        Started first = oidc.started(browser);
        Started second = oidc.started(browser);

        assertThat(second.state()).as("state").isNotEqualTo(first.state());
        assertThat(second.nonce()).as("nonce").isNotEqualTo(first.nonce());
        assertThat(second.query().get("code_challenge")).as("code_challenge")
                .isNotEqualTo(first.query().get("code_challenge"));
        assertThat(AuthChecks.cookieValue(second.response().setCookie(OidcFlow.COOKIE))).as("cookie value")
                .isNotEqualTo(AuthChecks.cookieValue(first.response().setCookie(OidcFlow.COOKIE)));
    }

    @Test
    @DisplayName("S11-AC-08 the 21st start from one IP within 60 seconds goes back to /login?oidc=busy without the "
            + "cookie, while a start from another IP goes to the provider")
    void twentyFirstStartFromOneIpIsBusy() {
        String ip = AuthChecks.freshIp();
        for (int n = 1; n <= 20; n++) {
            ApiResponse start = console().forwardedFor(ip).get(OidcFlow.START).send();
            assertThat(start.status()).as("start %d: %s", n, start.snippet()).isEqualTo(302);
            assertThat(start.header("Location")).as("Location of start %d", n)
                    .startsWith(oidc.provider().issuer() + "/");
        }
        clock.advance(Duration.ofSeconds(30));
        ApiClient limited = console().forwardedFor(ip);

        ApiResponse busy = oidc.start(limited);
        ApiResponse fromAnotherIp = oidc.start(console().forwardedFor(AuthChecks.freshIp()));

        OidcFlow.assertBackAtLogin(limited, busy, "busy");
        assertThat(busy.setCookie(OidcFlow.COOKIE)).as("sign-in cookie of %s", busy).isNull();
        oidc.started(fromAnotherIp);
    }

    @Test
    @DisplayName("S11-AC-14 a start made more than 10 minutes after an earlier one removes the earlier sign-in "
            + "request, and its own stays")
    void newStartRemovesRequestsOverTenMinutesOld() {
        Started old = oidc.started(console());
        assertThat(db.tables()).as("tables").contains("oidc_request");
        assertThat(requestRows(old.state())).as("rows of the first sign-in request").isEqualTo(1);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        Started fresh = oidc.started(console());

        assertThat(requestRows(old.state())).as("rows of the sign-in request started 10 minutes before").isZero();
        assertThat(requestRows(fresh.state())).as("rows of the new sign-in request").isEqualTo(1);
    }

    /**
     * Rows of the sign-in request of {@code state}: the table keeps only the SHA-256 of the state (design.md 1절), so
     * the row is found by the ways that hash can be written ({@link PasswordSteps#hashForms}).
     */
    private long requestRows(String state) {
        List<String> forms = PasswordSteps.hashForms(state);
        String marks = String.join(", ", Collections.nCopies(forms.size(), "?"));
        return db.count("select count(*) from oidc_request where state_hash in (" + marks + ")", forms.toArray());
    }
}
