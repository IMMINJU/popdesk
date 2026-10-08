package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.cookieAttributes;
import static io.popdesk.auth.AuthChecks.sessionCookies;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * An install behind HTTPS on the default port. The first admin's password here is exactly 10
 * characters, the shortest AUTH-5 allows.
 */
@TestPropertySource(properties = {
        "POPDESK_PUBLIC_SCHEME=https",
        "POPDESK_PUBLIC_PORT=443",
        "POPDESK_ADMIN_PASSWORD=" + HttpsPublicAddressTest.ADMIN_PASSWORD,
})
class HttpsPublicAddressTest extends BareAppTest {

    static final String ADMIN_PASSWORD = "Ten-chars1";

    @Test
    @DisplayName("S0-AC-28 a first admin with a 10-character password is created and signs in")
    void firstAdminWithTenCharacters() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.email()).isEqualTo(AppTest.ADMIN_EMAIL);
    }

    @Test
    @DisplayName("S0-AC-17 with POPDESK_PUBLIC_SCHEME=https the session and CSRF cookies are Secure")
    void cookiesAreSecure() {
        ApiClient browser = console();
        ApiResponse csrf = browser.get("/api/public/csrf").send();
        ApiResponse signIn = AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, ADMIN_PASSWORD, AuthChecks.freshIp());

        csrf.expectStatus(204);
        signIn.expectStatus(204);
        assertThat(csrf.setCookie(ApiClient.CSRF_COOKIE)).as("CSRF cookie").isNotNull();
        assertThat(sessionCookies(signIn)).as("session cookie in %s", signIn.headers("Set-Cookie")).isNotEmpty();
        for (ApiResponse response : List.of(csrf, signIn)) {
            for (String cookie : response.headers("Set-Cookie")) {
                Map<String, String> attributes = cookieAttributes(cookie);
                assertThat(attributes).as(cookie).containsKey("secure");
            }
        }
    }

    @Test
    @DisplayName("S0-AC-20 with https and port 443 links use https and leave out the port")
    void httpsLinksLeaveOutDefaultPort() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, ADMIN_PASSWORD);
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, fixtures.createCompany(admin).code());

        assertThat(agent.link()).isEqualTo("https://" + AppTest.CONSOLE_HOST + "/invite#token=" + agent.token());
        assertThat(customer.link())
                .isEqualTo("https://" + AppTest.PORTAL_HOST + "/invite#token=" + customer.token());
    }
}
