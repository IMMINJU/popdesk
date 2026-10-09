package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static io.popdesk.auth.AuthChecks.cookieAttributes;
import static io.popdesk.auth.AuthChecks.cookieName;
import static io.popdesk.auth.AuthChecks.cookieValue;
import static io.popdesk.auth.AuthChecks.sessionCookies;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Session and CSRF cookies, a new session ID at sign-in, and 403 for writes without the CSRF token. */
class SessionCookieTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-17 the session cookie set at sign-in is HttpOnly, SameSite=Lax, host-only, and not Secure on http")
    void sessionCookieAttributes() {
        ApiResponse signIn = AuthChecks.signIn(console(), AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());

        signIn.expectStatus(204);
        List<String> cookies = sessionCookies(signIn);
        assertThat(cookies).as("session cookie in %s", signIn.headers("Set-Cookie")).isNotEmpty();
        for (String cookie : cookies) {
            Map<String, String> attributes = cookieAttributes(cookie);
            assertThat(attributes).as(cookie).containsKey("httponly");
            assertThat(attributes.get("samesite")).as(cookie).isEqualToIgnoringCase("Lax");
            assertThat(attributes).as("host-only: %s", cookie).doesNotContainKey("domain");
            assertThat(attributes).as("no Secure with POPDESK_PUBLIC_SCHEME=http: %s", cookie)
                    .doesNotContainKey("secure");
        }
    }

    @Test
    @DisplayName("S0-AC-17 the CSRF cookie is readable by scripts, SameSite=Lax, host-only, and not Secure on http")
    void csrfCookieAttributes() {
        ApiClient console = console();
        ApiResponse onPortal = portal().get("/api/public/csrf").send();
        ApiResponse onConsole = console.get("/api/public/csrf").send();
        ApiResponse signIn = AuthChecks.signIn(console, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());

        onPortal.expectStatus(204);
        onConsole.expectStatus(204);
        signIn.expectStatus(204);
        for (ApiResponse response : List.of(onPortal, onConsole)) {
            assertThat(response.setCookie(ApiClient.CSRF_COOKIE)).as("CSRF cookie of %s", response).isNotNull();
        }
        for (ApiResponse response : List.of(onPortal, onConsole, signIn)) {
            String cookie = response.setCookie(ApiClient.CSRF_COOKIE);
            if (cookie == null) {
                continue;
            }
            Map<String, String> attributes = cookieAttributes(cookie);
            assertThat(attributes).as(cookie).doesNotContainKey("httponly");
            assertThat(attributes.get("samesite")).as(cookie).isEqualToIgnoringCase("Lax");
            assertThat(attributes).as("host-only: %s", cookie).doesNotContainKey("domain");
            assertThat(attributes).as("no Secure with POPDESK_PUBLIC_SCHEME=http: %s", cookie)
                    .doesNotContainKey("secure");
        }
    }

    @Test
    @DisplayName("S0-AC-17 signing in again gives a new session ID, and the old ID no longer works")
    void signInRenewsSessionId() {
        ApiClient browser = console();
        ApiResponse first = AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());
        first.expectStatus(204);
        assertThat(sessionCookies(first)).as("session cookie in %s", first.headers("Set-Cookie")).hasSize(1);
        String name = cookieName(sessionCookies(first).getFirst());
        String oldId = cookieValue(sessionCookies(first).getFirst());

        ApiResponse second = AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());

        second.expectStatus(204);
        assertThat(second.setCookie(name)).as("a new %s cookie at the second sign-in", name).isNotNull();
        assertThat(cookieValue(second.setCookie(name))).isNotEqualTo(oldId);
        browser.get("/api/console/me").expect(200);
        ApiClient withOldId = console();
        withOldId.setCookie(name, oldId);
        assertError(withOldId.get("/api/console/me").send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S0-AC-17 a session ID planted before sign-in is not the one the session gets")
    void plantedSessionIdIsReplaced() {
        ApiResponse probe = AuthChecks.signIn(console(), AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());
        probe.expectStatus(204);
        assertThat(sessionCookies(probe)).as("session cookie in %s", probe.headers("Set-Cookie")).hasSize(1);
        String name = cookieName(sessionCookies(probe).getFirst());
        String planted = "planted" + Unique.number();
        ApiClient victim = console();
        victim.setCookie(name, planted);

        ApiResponse signIn = AuthChecks.signIn(victim, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());

        signIn.expectStatus(204);
        assertThat(signIn.setCookie(name)).as("a new %s cookie", name).isNotNull();
        assertThat(cookieValue(signIn.setCookie(name))).isNotEqualTo(planted);
        assertThat(victim.cookie(name)).isNotEqualTo(Optional.of(planted));
    }

    @Test
    @DisplayName("S0-AC-17 sign-in without a CSRF token is 403 CSRF_TOKEN_INVALID and makes no session")
    void signInWithoutCsrfToken() {
        ApiClient browser = portal();
        Session customer = customerOfNewCompany();

        ApiResponse response = browser.post(AuthChecks.SIGN_IN).withoutCsrf()
                .header("X-Forwarded-For", AuthChecks.freshIp())
                .json(Map.of("email", customer.email(), "password", customer.password()))
                .send();

        assertError(response, 403, "CSRF_TOKEN_INVALID");
        assertThat(sessionCookies(response)).isEmpty();
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S0-AC-17 sign-in with a wrong CSRF token is 403 CSRF_TOKEN_INVALID and makes no session")
    void signInWithWrongCsrfToken() {
        ApiClient browser = portal();
        Session customer = customerOfNewCompany();
        browser.csrfToken();

        ApiResponse response = browser.post(AuthChecks.SIGN_IN).csrfToken("wrong" + Unique.number())
                .header("X-Forwarded-For", AuthChecks.freshIp())
                .json(Map.of("email", customer.email(), "password", customer.password()))
                .send();

        assertError(response, 403, "CSRF_TOKEN_INVALID");
        assertThat(sessionCookies(response)).isEmpty();
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S0-AC-17 a signed-in write without or with a wrong CSRF token is 403 and changes nothing")
    void signedInWriteNeedsCsrfToken() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        String missing = Unique.companyCode();
        String wrong = Unique.companyCode();

        ApiResponse withoutToken = admin.client().post("/api/console/companies").withoutCsrf()
                .json(Map.of("code", missing, "name", "No token", "legalName", "No token Ltd."))
                .send();
        ApiResponse wrongToken = admin.client().post("/api/console/companies").csrfToken("wrong" + Unique.number())
                .json(Map.of("code", wrong, "name", "Wrong token", "legalName", "Wrong token Ltd."))
                .send();

        assertError(withoutToken, 403, "CSRF_TOKEN_INVALID");
        assertError(wrongToken, 403, "CSRF_TOKEN_INVALID");
        assertError(admin.client().get("/api/console/companies/" + missing).send(), 404, "NOT_FOUND");
        assertError(admin.client().get("/api/console/companies/" + wrong).send(), 404, "NOT_FOUND");
    }

    private Session customerOfNewCompany() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        return fixtures.customer(admin, fixtures.createCompany(admin).code());
    }
}
