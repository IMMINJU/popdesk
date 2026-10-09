package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code X-Forwarded-*} from a trusted proxy. The test client connects from 127.0.0.1, which the
 * default {@code POPDESK_TRUSTED_PROXIES} trusts; {@link UntrustedProxyTest} covers the other case.
 */
class ForwardedHeadersTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-18 X-Forwarded-For from a trusted proxy is the client IP for the sign-in limit")
    void forwardedForFromTrustedProxyIsTheClientIp() {
        String limited = AuthChecks.freshIp();
        String other = AuthChecks.freshIp();
        String email = Unique.email("nobody");
        ApiClient browser = console();

        for (int attempt = 1; attempt <= 20; attempt++) {
            ApiResponse response = AuthChecks.signIn(browser, email, "wrong-Password-1", limited);
            assertThat(response.status()).as("attempt %d from %s: %s", attempt, limited, response.snippet())
                    .isEqualTo(401);
        }
        ApiResponse overLimit = AuthChecks.signIn(browser, email, "wrong-Password-1", limited);
        ApiResponse fromOther = AuthChecks.signIn(browser, email, "wrong-Password-1", other);

        assertError(overLimit, 429, "RATE_LIMITED");
        assertError(fromOther, 401, "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("S0-AC-19 a trusted proxy's X-Forwarded-Proto that differs from POPDESK_PUBLIC_SCHEME "
            + "logs one warning per (scheme, port) pair")
    void schemeMismatchWarnsOncePerPair() {
        AppLog.Mark mark = log.mark();

        for (ApiClient client : List.of(console(), console(), portal())) {
            client.get("/api/public/csrf").header("X-Forwarded-Proto", "https").expect(204);
        }

        awaitOneWarning(mark, "https");
    }

    @Test
    @DisplayName("S0-AC-19 a forwarded port that differs from POPDESK_PUBLIC_PORT logs one warning per pair, "
            + "from X-Forwarded-Port alone and from the port in X-Forwarded-Host alone")
    void portMismatchWarnsOncePerPair() {
        String fromPort = uniquePort();
        String fromHost = uniquePort();
        AppLog.Mark mark = log.mark();

        console().get("/api/public/csrf").header("X-Forwarded-Port", fromPort).expect(204);
        console().get("/api/public/csrf").header("X-Forwarded-Port", fromPort).expect(204);
        portal().get("/api/public/csrf").header("X-Forwarded-Port", fromPort).expect(204);
        console().get("/api/public/csrf").header("X-Forwarded-Host", AppTest.CONSOLE_HOST + ":" + fromHost).expect(204);
        portal().get("/api/public/csrf").header("X-Forwarded-Host", AppTest.PORTAL_HOST + ":" + fromHost).expect(204);

        awaitOneWarning(mark, fromPort);
        awaitOneWarning(mark, fromHost);
    }

    @Test
    @DisplayName("S0-AC-19 X-Forwarded-Port wins over the port in X-Forwarded-Host: "
            + "X-Forwarded-Port equal to POPDESK_PUBLIC_PORT logs no warning for the host's port")
    void forwardedPortWinsOverHostPort() {
        String hostPort = uniquePort();
        String other = uniquePort();
        AppLog.Mark mark = log.mark();

        console().get("/api/public/csrf").header("X-Forwarded-Port", "80")
                .header("X-Forwarded-Host", AppTest.CONSOLE_HOST + ":" + hostPort).expect(204);
        portal().get("/api/public/csrf").header("X-Forwarded-Port", "80")
                .header("X-Forwarded-Host", AppTest.PORTAL_HOST + ":" + hostPort).expect(204);
        // X-Forwarded-Port that differs, with the public port in X-Forwarded-Host: the warning names it.
        console().get("/api/public/csrf").header("X-Forwarded-Port", other)
                .header("X-Forwarded-Host", AppTest.CONSOLE_HOST + ":80").expect(204);

        awaitOneWarning(mark, other);
        Wait.holds("no warning naming " + hostPort, Duration.ofSeconds(3),
                () -> warningsWith(mark, hostPort).isEmpty());
    }

    @Test
    @DisplayName("S0-AC-19 a forwarded scheme and port equal to POPDESK_PUBLIC_SCHEME and POPDESK_PUBLIC_PORT "
            + "log no warning")
    void matchingSchemeAndPortDoNotWarn() {
        AppLog.Mark mark = log.mark();
        String path = "/api/public/csrf";

        console().get(path).header("X-Forwarded-Proto", "http").header("X-Forwarded-Port", "80").expect(204);
        portal().get(path).header("X-Forwarded-Proto", "http").header("X-Forwarded-Host", AppTest.PORTAL_HOST + ":80")
                .expect(204);
        console().get(path).header("X-Forwarded-Proto", "http").header("X-Forwarded-Host", AppTest.CONSOLE_HOST)
                .expect(204);

        Wait.holds("no warning naming the scheme", Duration.ofSeconds(3),
                () -> warningsWith(mark, "http").isEmpty());
    }

    private static String uniquePort() {
        return Integer.toString(20000 + (int) Unique.number());
    }

    private void awaitOneWarning(AppLog.Mark mark, String value) {
        Wait.until("a warning naming " + value, () -> warningsWith(mark, value).isEmpty() ? null : true);
        Wait.holds("only one warning naming " + value, Duration.ofSeconds(3),
                () -> warningsWith(mark, value).size() <= 1);
        assertThat(warningsWith(mark, value)).as("warnings naming %s since the first request", value).hasSize(1);
    }

    private List<String> warningsWith(AppLog.Mark mark, String text) {
        return log.lines(mark).stream().filter(l -> l.contains("WARN") && l.contains(text)).toList();
    }
}
