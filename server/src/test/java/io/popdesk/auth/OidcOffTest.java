package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The shared bare context has no OIDC settings: OIDC sign-in is off for both apps. The sign-in pages are told so
 * (S11-AC-03), and the two OIDC paths answer 404 there (S11-AC-06).
 */
class OidcOffTest extends BareAppTest {

    @Test
    @DisplayName("S11-AC-03 without OIDC settings sign-in-options answers {oidc: false} on both hosts, with no other "
            + "field, and no-store")
    void signInOptionsSayOff() {
        for (ApiClient browser : List.of(portal(), console())) {
            ApiResponse options = browser.get(OidcFlow.OPTIONS).expect(200);
            JsonNode body = options.json();

            assertThat(OidcFlow.fieldNames(body)).as("fields of %s on %s", body, browser.host()).isEqualTo(Set.of("oidc"));
            assertThat(body.path("oidc").isBoolean() && !body.path("oidc").booleanValue())
                    .as("oidc is false in %s on %s", body, browser.host()).isTrue();
            assertThat(options.header("Cache-Control")).as("Cache-Control of %s", options).contains("no-store");
        }
    }

    @Test
    @DisplayName("S11-AC-06 without OIDC settings the start and the callback answer 404 NOT_FOUND on both hosts, "
            + "with or without a query, and set no sign-in cookie")
    void startAndCallbackAre404() {
        for (ApiClient browser : List.of(portal(), console())) {
            List<ApiResponse> answers = new ArrayList<>(List.of(
                    browser.get(OidcFlow.START).send(),
                    browser.get(OidcFlow.START).query("next", "/issues").send(),
                    browser.get(OidcFlow.CALLBACK).send(),
                    browser.get(OidcFlow.CALLBACK).query("code", "inv-code-off").query("state", "inv-state-off")
                            .send(),
                    browser.get(OidcFlow.CALLBACK).query("error", "access_denied").query("state", "inv-state-off")
                            .send()));

            for (ApiResponse answer : answers) {
                assertError(answer, 404, "NOT_FOUND");
                assertThat(answer.setCookie(OidcFlow.COOKIE)).as("sign-in cookie of %s", answer).isNull();
            }
        }
    }
}
