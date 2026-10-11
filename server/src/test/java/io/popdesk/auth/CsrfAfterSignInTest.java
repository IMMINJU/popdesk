package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CSRF token changes at sign-in, so a token someone saw or planted before it is of no use after. */
class CsrfAfterSignInTest extends BareAppTest {

    @Test
    @DisplayName("S1A-AC-69 sign-in gives a new CSRF token, and a write with the token from before it gets 403")
    void tokenFromBeforeSignInIsRefused() {
        ApiClient browser = console();
        String before = browser.csrfToken();
        AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD, AuthChecks.freshIp())
                .expectStatus(204);
        String code = Unique.companyCode();
        Map<String, String> company = Map.of("code", code, "name", "Company " + code, "legalName", "Company " + code);

        String after = browser.csrfToken();
        ApiResponse withOldToken = browser.post("/api/console/companies").csrfToken(before).json(company).send();

        assertThat(after).as("CSRF token after sign-in").isNotEqualTo(before);
        assertError(withOldToken, 403, "CSRF_TOKEN_INVALID");
        assertError(browser.get("/api/console/companies/" + code).send(), 404, "NOT_FOUND");
        browser.post("/api/console/companies").json(company).expect(201);
    }
}
