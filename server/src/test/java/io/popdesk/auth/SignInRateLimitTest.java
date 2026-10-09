package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** At most 20 sign-in attempts per IP in 60 seconds, counted in memory only. */
class SignInRateLimitTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-24, S0-AC-16 the 21st sign-in attempt from one IP within 60 seconds gets 429 with "
            + "Retry-After and the security headers, even with the right password")
    void twentyFirstAttemptIsLimited() {
        String ip = AuthChecks.freshIp();
        String email = Unique.email("nobody");
        ApiClient browser = console();

        for (int attempt = 1; attempt <= 20; attempt++) {
            ApiResponse response = AuthChecks.signIn(browser, email, "wrong-Password-1", ip);
            assertThat(response.status()).as("attempt %d: %s", attempt, response.snippet()).isEqualTo(401);
        }
        ApiResponse twentyFirst = AuthChecks.signIn(browser, email, "wrong-Password-1", ip);
        ApiResponse rightPassword = AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD, ip);

        assertError(twentyFirst, 429, "RATE_LIMITED");
        assertRetryAfter(twentyFirst);
        AuthChecks.assertSecurityHeaders(twentyFirst);
        assertError(rightPassword, 429, "RATE_LIMITED");
        assertRetryAfter(rightPassword);
        assertError(browser.get("/api/console/me").send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S0-AC-24 the limit counts 60 seconds on the app's clock: after they pass, sign-in works again")
    void limitEndsAfterSixtySeconds() {
        String ip = AuthChecks.freshIp();
        String email = Unique.email("nobody");
        ApiClient browser = console();
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertError(AuthChecks.signIn(browser, email, "wrong-Password-1", ip), 401, "SIGN_IN_FAILED");
        }
        assertError(AuthChecks.signIn(browser, email, "wrong-Password-1", ip), 429, "RATE_LIMITED");

        clock.advance(Duration.ofSeconds(61));

        assertError(AuthChecks.signIn(browser, email, "wrong-Password-1", ip), 401, "SIGN_IN_FAILED");
        AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD, ip).expectStatus(204);
        browser.get("/api/console/me").expect(200);
    }

    @Test
    @DisplayName("S0-AC-24 sign-in attempts do not store the client IP in the database")
    void clientIpIsNotStored() {
        String ip = AuthChecks.freshIp();
        ApiClient browser = console();

        assertError(AuthChecks.signIn(browser, Unique.email("nobody"), "wrong-Password-1", ip), 401,
                "SIGN_IN_FAILED");
        assertError(AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, "wrong-Password-1", ip), 401, "SIGN_IN_FAILED");
        AuthChecks.signIn(browser, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD, ip).expectStatus(204);
        browser.get("/api/console/me").expect(200);

        assertThat(AuthChecks.rowsContaining(db, ip)).as("rows holding %s", ip).isEmpty();
    }

    private static void assertRetryAfter(ApiResponse response) {
        String retryAfter = response.header("Retry-After");
        assertThat(retryAfter).as("Retry-After of %s", response).isNotBlank();
        String value = retryAfter.strip();
        if (value.matches("\\d+")) {
            assertThat(Long.parseLong(value)).as("Retry-After in seconds").isBetween(1L, 60L);
        } else {
            assertThatCode(() -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(value))
                    .as("Retry-After is seconds or an HTTP date: %s", value)
                    .doesNotThrowAnyException();
        }
    }
}
