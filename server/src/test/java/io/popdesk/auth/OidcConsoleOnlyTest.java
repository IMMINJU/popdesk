package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * With {@code OIDC_APPS=console} OIDC sign-in is on for the console and off for the portal: the sign-in pages are
 * told so (S11-AC-03), and the portal's two OIDC paths answer 404 while the console's work (S11-AC-06).
 */
class OidcConsoleOnlyTest extends OidcCraftedAppTest {

    @Test
    @DisplayName("S11-AC-03 with OIDC_APPS=console sign-in-options answers {oidc: false} on the portal host and "
            + "{oidc: true} on the console host")
    void signInOptionsSayConsoleOnly() {
        for (ApiClient browser : List.of(portal(), console())) {
            boolean on = browser.host().equals(CONSOLE_HOST);
            JsonNode body = browser.get(OidcFlow.OPTIONS).expect(200).json();

            assertThat(OidcFlow.fieldNames(body)).as("fields of %s on %s", body, browser.host())
                    .isEqualTo(Set.of("oidc"));
            assertThat(body.path("oidc").isBoolean() && body.path("oidc").booleanValue() == on)
                    .as("oidc is %s in %s on %s", on, body, browser.host()).isTrue();
        }
    }

    @Test
    @DisplayName("S11-AC-06 with OIDC_APPS=console the portal's start and callback answer 404 NOT_FOUND without the "
            + "sign-in cookie, while the console's start goes to the provider and its callback is not 404")
    void portalPathsAre404() {
        ApiClient portal = portal();
        List<ApiResponse> answers = List.of(
                portal.get(OidcFlow.START).send(),
                portal.get(OidcFlow.CALLBACK).query("code", "inv-code-portal").query("state", "inv-state-portal")
                        .send(),
                portal.get(OidcFlow.CALLBACK).query("error", "access_denied").query("state", "inv-state-portal")
                        .send());
        ApiClient console = console();

        for (ApiResponse answer : answers) {
            assertError(answer, 404, "NOT_FOUND");
            assertThat(answer.setCookie(OidcFlow.COOKIE)).as("sign-in cookie of %s", answer).isNull();
        }
        oidc.started(console);
        OidcFlow.assertBackAtLogin(console, oidc.callback(console, Map.of("code", "inv-code-console",
                "state", PasswordSteps.tokenNeverIssued())), "failed");
    }
}
