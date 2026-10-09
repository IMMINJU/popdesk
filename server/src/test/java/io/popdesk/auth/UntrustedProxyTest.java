package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;

/**
 * An install whose only trusted proxy is 192.0.2.10, so the test client on 127.0.0.1 is not trusted
 * and every request counts against 127.0.0.1 whatever {@code X-Forwarded-For} says. The public port
 * is 8080, not the default for http. The first admin's password is 72 characters, the longest AUTH-5
 * allows.
 *
 * <p>No other class uses this setup. The last test uses up the sign-in limit of 127.0.0.1, so it runs
 * last.
 */
@TestPropertySource(properties = {
        "POPDESK_TRUSTED_PROXIES=192.0.2.10",
        "POPDESK_PUBLIC_PORT=8080",
        "POPDESK_ADMIN_PASSWORD=" + UntrustedProxyTest.ADMIN_PASSWORD,
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UntrustedProxyTest extends BareAppTest {

    static final String ADMIN_PASSWORD =
            "Seventy-two-characters-are-the-most-a-password-may-have-under-AUTH-5-ok1";

    @Test
    @Order(1)
    @DisplayName("S0-AC-28 a first admin with a 72-character password is created and signs in")
    void firstAdminWithSeventyTwoCharacters() {
        assertThat(ADMIN_PASSWORD).hasSize(72);

        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-20 with http and port 8080 links keep the port")
    void linksKeepNonDefaultPort() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, ADMIN_PASSWORD);
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, fixtures.createCompany(admin).code());

        assertThat(agent.link())
                .isEqualTo("http://" + AppTest.CONSOLE_HOST + ":8080/invite#token=" + agent.token());
        assertThat(customer.link())
                .isEqualTo("http://" + AppTest.PORTAL_HOST + ":8080/invite#token=" + customer.token());
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-18, S0-AC-19 X-Forwarded-Proto and -Port from an address that is not a trusted proxy are ignored "
            + "and logs no warning")
    void untrustedForwardedProtoIsIgnored() {
        AppLog.Mark mark = log.mark();

        String port = Integer.toString(20000 + (int) Unique.number());

        console().get("/api/public/csrf").header("X-Forwarded-Proto", "https").expect(204);
        portal().get("/api/public/csrf").header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", port)
                .expect(204);

        Wait.holds("no warning naming https or " + port, Duration.ofSeconds(3),
                () -> log.lines(mark).stream()
                        .noneMatch(l -> l.contains("WARN") && (l.contains("https") || l.contains(port))));
    }

    @Test
    @Order(4)
    @DisplayName("S0-AC-14, S0-AC-18 X-Forwarded-Host from an address that is not a trusted proxy "
            + "does not decide the host")
    void untrustedForwardedHostIsIgnored() {
        console().get("/api/public/csrf").header("X-Forwarded-Host", "<svg onload=1>").expect(204);
        AuthChecks.assertError(console().get("/api/console/me").header("X-Forwarded-Host", AppTest.PORTAL_HOST)
                .send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @Order(5)
    @DisplayName("S0-AC-18 X-Forwarded-For from an address that is not a trusted proxy does not change "
            + "the IP the sign-in limit counts")
    void untrustedForwardedForIsIgnored() {
        String email = Unique.email("nobody");
        ApiClient browser = console();
        ApiResponse limited = null;
        for (int attempt = 1; attempt <= 21 && limited == null; attempt++) {
            ApiResponse response = AuthChecks.signIn(browser, email, "wrong-Password-1", AuthChecks.freshIp());
            if (response.status() == 429) {
                limited = response;
            } else {
                assertError(response, 401, "SIGN_IN_FAILED");
            }
        }
        assertThat(limited).as("a 429 within 21 attempts from 127.0.0.1").isNotNull();

        ApiResponse otherForwardedIp = AuthChecks.signIn(browser, email, "wrong-Password-1", AuthChecks.freshIp());

        assertError(otherForwardedIp, 429, "RATE_LIMITED");
    }
}
