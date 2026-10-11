package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * An install whose trusted proxies are {@code 127.0.0.0/9}, a range whose prefix does not end on a
 * byte: 127.0.0.0 to 127.127.255.255. The usual test client on 127.0.0.1 is inside it, so fixtures
 * work as in the other setups.
 *
 * <p>The tests connect from loopback addresses of their own, which the operating system lets a
 * client bind: 127.100.0.1 is inside the range (and outside 127.0.0.0/16), 127.200.0.1 is outside it
 * (and inside 127.0.0.0/8). Whether the app trusts the address shows in the sign-in limit per IP: it
 * counts {@code X-Forwarded-For} from a trusted proxy, and the connection's address otherwise. Each
 * test has an address no other test connects from, so their counts do not meet.
 *
 * <p>No other class uses this setup.
 */
@TestPropertySource(properties = "POPDESK_TRUSTED_PROXIES=127.0.0.0/9")
class TrustedProxyPrefixTest extends BareAppTest {

    private static final String INSIDE = "127.100.0.1";
    private static final String OUTSIDE = "127.200.0.1";

    @Test
    @DisplayName("S1A-AC-73 an address inside a /9 range is a trusted proxy: its X-Forwarded-For is the client IP "
            + "of the sign-in limit")
    void addressInsideTheRangeIsTrusted() {
        Caller proxy = new Caller(INSIDE);
        String limited = AuthChecks.freshIp();
        String other = AuthChecks.freshIp();
        String email = Unique.email("nobody");
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(proxy.signIn(email, limited).statusCode()).as("attempt %d for %s", attempt, limited)
                    .isEqualTo(401);
        }

        HttpResponse<String> overLimit = proxy.signIn(email, limited);
        HttpResponse<String> forOther = proxy.signIn(email, other);

        assertThat(overLimit.statusCode()).as("21st attempt for %s: %s", limited, overLimit.body()).isEqualTo(429);
        assertThat(overLimit.body()).contains("RATE_LIMITED");
        assertThat(forOther.statusCode()).as("first attempt for %s: %s", other, forOther.body()).isEqualTo(401);
    }

    @Test
    @DisplayName("S1A-AC-73 an address outside a /9 range, though inside the /8 around it, is not a trusted proxy: "
            + "its X-Forwarded-For does not change the IP the sign-in limit counts")
    void addressOutsideTheRangeIsNotTrusted() {
        Caller stranger = new Caller(OUTSIDE);
        String email = Unique.email("nobody");
        for (int attempt = 1; attempt <= 20; attempt++) {
            assertThat(stranger.signIn(email, AuthChecks.freshIp()).statusCode()).as("attempt %d", attempt)
                    .isEqualTo(401);
        }

        HttpResponse<String> twentyFirst = stranger.signIn(email, AuthChecks.freshIp());

        assertThat(twentyFirst.statusCode()).as("21st attempt from %s: %s", OUTSIDE, twentyFirst.body())
                .isEqualTo(429);
        assertThat(twentyFirst.body()).contains("RATE_LIMITED");
    }

    /** A client whose connections come from one loopback address, signing in on the console host. */
    private final class Caller {

        private final HttpClient http;
        private final String csrfToken;

        Caller(String localAddress) {
            ApiClient browser = console();
            csrfToken = browser.csrfToken();
            try {
                http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .localAddress(InetAddress.getByName(localAddress))
                        .build();
            } catch (IOException e) {
                throw new AssertionError("Not an address: " + localAddress, e);
            }
        }

        /** One sign-in attempt with a wrong password, carrying {@code X-Forwarded-For: forwardedFor}. */
        HttpResponse<String> signIn(String email, String forwardedFor) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + AuthChecks.SIGN_IN))
                    .header("Host", CONSOLE_HOST)
                    .header(ApiClient.FORWARDED_FOR, forwardedFor)
                    .header(ApiClient.CSRF_HEADER, csrfToken)
                    .header("Cookie", ApiClient.CSRF_COOKIE + "=" + csrfToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"" + email + "\",\"password\":\"wrong-Password-1\"}"))
                    .build();
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new AssertionError("No response to a sign-in from this address: " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted during a sign-in", e);
            }
        }
    }
}
