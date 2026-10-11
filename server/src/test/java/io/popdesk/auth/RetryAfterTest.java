package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The limits per IP of 20 requests in 60 seconds (COM-8) and what {@code Retry-After} says. The
 * requests name an email no account has, so each one gets the answer of its endpoint for "nothing
 * happened": 401 for sign-in, 202 for a password reset request.
 */
class RetryAfterTest extends BareAppTest {

    /** Requests limited per IP. */
    enum Limited {
        SIGN_IN(401) {
            @Override
            ApiResponse send(ApiClient browser, String email, String ip) {
                return AuthChecks.signIn(browser, email, "wrong-Password-1", ip);
            }
        },
        PASSWORD_RESET_REQUEST(202) {
            @Override
            ApiResponse send(ApiClient browser, String email, String ip) {
                return browser.post(PasswordSteps.REQUEST).header("X-Forwarded-For", ip).json(Map.of("email", email))
                        .send();
            }
        };

        private final int usualStatus;

        Limited(int usualStatus) {
            this.usualStatus = usualStatus;
        }

        abstract ApiResponse send(ApiClient browser, String email, String ip);
    }

    @ParameterizedTest
    @EnumSource(Limited.class)
    @DisplayName("S1A-AC-13, S1A-AC-71 the 21st request from one IP within 60 seconds gets 429 with Retry-After as "
            + "the seconds left of the window, and a request after those seconds is not limited")
    void retryAfterIsTheRestOfTheWindow(Limited request) {
        String ip = AuthChecks.freshIp();
        String email = Unique.email("nobody");
        ApiClient browser = portal();
        for (int n = 1; n <= 20; n++) {
            ApiResponse response = request.send(browser, email, ip);
            assertThat(response.status()).as("request %d: %s", n, response.snippet()).isEqualTo(request.usualStatus);
        }
        clock.advance(Duration.ofSeconds(25));

        ApiResponse limited = request.send(browser, email, ip);

        assertError(limited, 429, "RATE_LIMITED");
        String retryAfter = limited.header("Retry-After");
        assertThat(retryAfter).as("Retry-After of %s", limited).isNotNull().matches("\\d+");
        long seconds = Long.parseLong(retryAfter);
        assertThat(seconds).as("seconds left of a 60-second window that began 25 seconds ago").isBetween(1L, 35L);
        clock.advance(Duration.ofSeconds(seconds));
        ApiResponse after = request.send(browser, email, ip);
        assertThat(after.status()).as("after Retry-After passed: %s", after.snippet()).isEqualTo(request.usualStatus);
    }
}
