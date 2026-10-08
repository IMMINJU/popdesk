package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Sign-in on each app's host, and one 401 body for every way it can fail. */
class SignInTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-21 an admin signs in on the console; email case and surrounding spaces are ignored")
    void adminSignsInOnConsole() {
        ApiClient browser = console();
        String typed = "  " + AppTest.ADMIN_EMAIL.toUpperCase(Locale.ROOT) + " \t";

        ApiResponse signIn = AuthChecks.signIn(browser, typed, AppTest.ADMIN_PASSWORD, AuthChecks.freshIp());

        signIn.expectStatus(204);
        ApiResponse me = browser.get("/api/console/me").expect(200);
        assertThat(me.jsonText("email")).isEqualTo(AppTest.ADMIN_EMAIL);
        assertThat(me.jsonText("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("S0-AC-21 a customer signs in on the portal")
    void customerSignsInOnPortal() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        ApiClient browser = portal();

        ApiResponse signIn = AuthChecks.signIn(browser, " " + customer.email().toUpperCase(Locale.ROOT),
                customer.password(), AuthChecks.freshIp());

        signIn.expectStatus(204);
        ApiResponse me = browser.get("/api/portal/me").expect(200);
        assertThat(me.jsonLong("id")).isEqualTo(customer.accountId());
        assertThat(me.jsonText("email")).isEqualTo(customer.email());
    }

    @Test
    @DisplayName("S0-AC-21 an agent signs in on the console")
    void agentSignsInOnConsole() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();

        ApiResponse signIn = AuthChecks.signIn(browser, agent.email(), agent.password(), AuthChecks.freshIp());

        signIn.expectStatus(204);
        ApiResponse me = browser.get("/api/console/me").expect(200);
        assertThat(me.jsonLong("id")).isEqualTo(agent.accountId());
        assertThat(me.jsonText("role")).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("S0-AC-22 unknown email, wrong password, invited account, and the other app's account "
            + "all get the same 401 body and no session")
    void everyFailureGetsTheSameBody() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        String company = fixtures.createCompany(admin).code();
        Session customer = fixtures.customer(admin, company);
        Session agent = fixtures.agent(admin, company);
        Invitation invited = fixtures.invite(admin, Role.CUSTOMER, company);

        Map<String, Attempt> attempts = new LinkedHashMap<>();
        attempts.put("unknown email", new Attempt(portal(), Unique.email("nobody"), Fixtures.PASSWORD));
        attempts.put("wrong password", new Attempt(portal(), customer.email(), "wrong-" + Fixtures.PASSWORD));
        attempts.put("invited account", new Attempt(portal(), invited.email(), Fixtures.PASSWORD));
        attempts.put("customer on the console", new Attempt(console(), customer.email(), customer.password()));
        attempts.put("agent on the portal", new Attempt(portal(), agent.email(), agent.password()));
        attempts.put("admin on the portal", new Attempt(portal(), AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD));

        ApiResponse reference = null;
        for (Map.Entry<String, Attempt> entry : attempts.entrySet()) {
            Attempt attempt = entry.getValue();
            ApiResponse response = AuthChecks.signIn(attempt.client(), attempt.email(), attempt.password(),
                    AuthChecks.freshIp());

            assertError(response, 401, "SIGN_IN_FAILED");
            assertThat(AuthChecks.sessionCookies(response)).as(entry.getKey()).isEmpty();
            if (reference == null) {
                reference = response;
            } else {
                assertThat(response.body()).as("body for %s vs %s", entry.getKey(), "unknown email")
                        .isEqualTo(reference.body());
                assertThat(response.header("Content-Type")).as(entry.getKey())
                        .isEqualTo(reference.header("Content-Type"));
            }
            String me = attempt.client().host().equals(AppTest.PORTAL_HOST) ? "/api/portal/me" : "/api/console/me";
            assertError(attempt.client().get(me).send(), 401, "UNAUTHENTICATED");
        }
    }

    private record Attempt(ApiClient client, String email, String password) {}
}
