package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertSecurityHeaders;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CSP, nosniff, and no referrer on every response, errors included. */
class SecurityHeadersTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-16 error responses (400, 401, 403, 404) carry CSP, nosniff, and no-referrer")
    void errorResponsesCarrySecurityHeaders() {
        ApiClient console = console();
        ApiResponse unauthenticated = console.get("/api/console/me").send();
        ApiResponse otherAppPath = console.get("/api/portal/me").send();
        ApiResponse invalidInput = console.post(AuthChecks.SIGN_IN)
                .header("X-Forwarded-For", AuthChecks.freshIp())
                .json(Map.of("email", "", "password", ""))
                .send();
        ApiResponse noCsrf = portal().post(AuthChecks.SIGN_IN).withoutCsrf()
                .json(Map.of("email", AppTest.ADMIN_EMAIL, "password", AppTest.ADMIN_PASSWORD))
                .send();
        ApiResponse unknownPath = portal().get("/api/portal/no-such-path").send();

        expect(unauthenticated, 401);
        expect(otherAppPath, 404);
        expect(invalidInput, 400);
        expect(noCsrf, 403);
        expect(unknownPath, 404);
        for (ApiResponse response : List.of(unauthenticated, otherAppPath, invalidInput, noCsrf, unknownPath)) {
            assertSecurityHeaders(response);
        }
    }

    @Test
    @DisplayName("S0-AC-16 successful responses (200, 204) carry CSP, nosniff, and no-referrer")
    void successResponsesCarrySecurityHeaders() {
        ApiClient console = console();
        ApiResponse portalCsrf = portal().get("/api/public/csrf").send();
        ApiResponse consoleCsrf = console.get("/api/public/csrf").send();
        ApiResponse signIn = AuthChecks.signIn(console, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD,
                AuthChecks.freshIp());
        ApiResponse me = console.get("/api/console/me").send();

        expect(portalCsrf, 204);
        expect(consoleCsrf, 204);
        expect(signIn, 204);
        expect(me, 200);
        for (ApiResponse response : List.of(portalCsrf, consoleCsrf, signIn, me)) {
            assertSecurityHeaders(response);
        }
    }

    private static void expect(ApiResponse response, int status) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(status);
    }
}
