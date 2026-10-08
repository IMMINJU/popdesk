package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static io.popdesk.auth.AuthChecks.assertSecurityHeaders;
import static io.popdesk.auth.AuthChecks.normalizedContentType;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each host serves only its own app's API, and a host that is neither gets a plain text note.
 *
 * <p>Tomcat answers 400 itself, before the app, for a {@code Host} with characters outside letters,
 * digits, dots, and hyphens ({@code <svg onload=1>}, quotes). The unknown-host checks therefore use a
 * valid name in mixed case to see that the note keeps the received value as it came.
 */
class HostRoutingTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-13 the portal host answers 404 for console API paths, signed in or not")
    void portalHostHidesConsoleApi() {
        ApiClient anonymous = portal();
        anonymous.get("/api/public/csrf").expect(204);

        assertError(anonymous.get("/api/console/me").send(), 404, "NOT_FOUND");
        assertError(anonymous.get("/api/console/companies").send(), 404, "NOT_FOUND");
        assertError(anonymous.post("/api/console/companies")
                .json(Map.of("code", Unique.companyCode(), "name", "A", "legalName", "A")).send(), 404, "NOT_FOUND");

        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        assertError(customer.client().get("/api/console/me").send(), 404, "NOT_FOUND");
        assertError(customer.client().get("/api/console/issues").send(), 404, "NOT_FOUND");
        assertError(customer.client().get("/api/console/companies/" + company.code()).send(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-13 the console host answers 404 for portal API paths, signed in or not")
    void consoleHostHidesPortalApi() {
        ApiClient anonymous = console();
        anonymous.get("/api/public/csrf").expect(204);

        assertError(anonymous.get("/api/portal/me").send(), 404, "NOT_FOUND");
        assertError(anonymous.get("/api/portal/issues").send(), 404, "NOT_FOUND");

        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Company company = fixtures.createCompany(admin);
        assertError(admin.client().get("/api/portal/me").send(), 404, "NOT_FOUND");
        assertError(admin.client().get("/api/portal/companies").send(), 404, "NOT_FOUND");
        assertError(admin.client().post("/api/portal/issues")
                .json(Map.of("companyCode", company.code(), "title", "Wrong host", "content", "x")).send(),
                404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-14 an unknown Host gets 404 in plain text naming both hosts and the Host as received, "
            + "whatever Accept says")
    void unknownHostGetsPlainTextNote() {
        String host = "Unknown-" + Unique.number() + ".Example.Test";

        ApiResponse response = client(host).get("/api/public/csrf").header("Accept", "application/json").send();

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(normalizedContentType(response)).isEqualTo("text/plain;charset=utf-8");
        assertThat(response.text())
                .contains(AppTest.PORTAL_HOST)
                .contains(AppTest.CONSOLE_HOST)
                .contains(host);
        assertSecurityHeaders(response);
    }

    @Test
    @DisplayName("S0-AC-14 an unknown Host gets the same plain text 404 on any path and method")
    void unknownHostNoteOnAnyPath() {
        String host = "other-" + Unique.number() + ".example.test";
        ApiClient client = client(host);

        ApiResponse signIn = client.post("/api/public/sign-in").withoutCsrf()
                .header("Accept", "text/html")
                .json(Map.of("email", AppTest.ADMIN_EMAIL, "password", AppTest.ADMIN_PASSWORD))
                .send();
        ApiResponse root = client.get("/").send();
        ApiResponse console = client.get("/api/console/me").header("Accept", "application/json").send();

        for (ApiResponse response : new ApiResponse[] {signIn, root, console}) {
            assertThat(response.status()).as(response.toString()).isEqualTo(404);
            assertThat(normalizedContentType(response)).as(response.toString()).isEqualTo("text/plain;charset=utf-8");
            assertThat(response.text()).as(response.toString())
                    .contains(AppTest.PORTAL_HOST)
                    .contains(AppTest.CONSOLE_HOST)
                    .contains(host);
            assertSecurityHeaders(response);
        }
    }
}
