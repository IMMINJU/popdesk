package io.popdesk.helpdesk.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The window of the limit per IP and how the time left in it becomes {@code Retry-After} (S1A-AC-71):
 * whole seconds rounded up, so that a request sent after them is outside the window.
 */
class IpRateLimitTest {

    private static final Instant START = Instant.parse("2026-10-11T00:00:00Z");

    @Test
    @DisplayName("S1A-AC-71 the 21st attempt gets the time until the first attempt leaves the window, and is "
            + "not counted")
    void timeLeftIsUntilTheOldestAttemptLeaves() {
        IpRateLimit limit = new IpRateLimit(Clock.fixed(START, ZoneOffset.UTC));
        for (int n = 1; n <= IpRateLimit.LIMIT; n++) {
            assertThat(limit.attempt("203.0.113.7")).as("attempt %d", n).isEmpty();
        }

        Optional<Duration> left = limit.attempt("203.0.113.7");

        assertThat(left).contains(IpRateLimit.WINDOW);
        assertThat(limit.attempt("203.0.113.8")).as("another address").isEmpty();
    }

    @ParameterizedTest(name = "{0} ms left is Retry-After {1}")
    @CsvSource({"35000, 35", "34001, 35", "34999, 35", "1, 1", "0, 1", "60000, 60"})
    @DisplayName("S1A-AC-71 Retry-After is the time left in whole seconds, rounded up and at least 1")
    void retryAfterRoundsUp(long millisLeft, long seconds) {
        assertThat(ApiException.rateLimited(Duration.ofMillis(millisLeft)).retryAfterSeconds()).isEqualTo(seconds);
    }
}
