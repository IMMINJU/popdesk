package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OIDC set up correctly, with a provider that is not there: {@code OIDC_ISSUER} on a local port nothing listens on.
 * The app does not reach for the provider while it starts (S11-AC-02), so it starts and password sign-in works; an
 * {@code http} issuer gets a warning at startup. A sign-in started then cannot read the discovery document and goes
 * back to the sign-in page with {@code oidc=unavailable} (S11-AC-07).
 *
 * <p>The provider of the OIDC context ({@code support.OidcProvider}) is not stopped for this: the other OIDC tests
 * share it, and the app keeps a discovery document it read for an hour. A closed port is a provider down from the
 * first start on.
 */
class OidcProviderDownStartTest extends OwnStartTest {

    private static final String SECRET = "rst-oidc-down-secret-3f81d0";

    @Test
    @DisplayName("S11-AC-02 with OIDC set up and the provider down the app starts, is ready, the first admin signs in "
            + "with the password, and an http OIDC_ISSUER gets a WARN line at startup that names it")
    void startsWithTheProviderDown() {
        try (Boot app = Boot.start(settings())) {
            app.assertStarted();
            assertReady(app);
            ApiResponse signIn = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);

            assertThat(signIn.status()).as(signIn + " " + signIn.snippet()).isEqualTo(204);
            assertThat(app.logLines()).as("a WARN line at startup about the http OIDC_ISSUER")
                    .anyMatch(line -> line.contains("WARN") && line.contains("OIDC_ISSUER"));
            assertThat(app.log()).as("the log of the start").doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("S11-AC-07 with the provider down a sign-in start goes back to /login?oidc=unavailable without the "
            + "sign-in cookie, again on a second start, and password sign-in still works")
    void startWithTheProviderDownIsUnavailable() {
        try (Boot app = Boot.start(settings())) {
            app.assertStarted();
            assertReady(app);
            ApiClient browser = app.console();

            ApiResponse first = browser.get("/api/public/oidc/start").send();
            ApiResponse second = app.console().get("/api/public/oidc/start").send();

            for (ApiResponse start : new ApiResponse[] {first, second}) {
                assertThat(start.status()).as("status of %s: %s", start, start.snippet()).isEqualTo(302);
                assertThat(location(start)).as("Location of %s", start).isEqualTo("/login?oidc=unavailable");
                assertThat(start.setCookie("POPDESK_OIDC")).as("sign-in cookie of %s", start).isNull();
            }
            ApiResponse signIn = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
            assertThat(signIn.status()).as(signIn + " " + signIn.snippet()).isEqualTo(204);
        }
    }

    private static Settings settings() {
        return Settings.firstInstall(postgres.newDatabase())
                .set("OIDC_ISSUER", "http://localhost:" + closedPort() + "/default")
                .set("OIDC_CLIENT_ID", "start-oidc-client")
                .set("OIDC_CLIENT_SECRET", SECRET)
                .set("OIDC_APPS", "both");
    }

    /** The path and query of a {@code Location} on the console's public origin, or the value as it is. */
    private static String location(ApiResponse response) {
        String location = response.header("Location");
        String origin = "http://" + AppTest.CONSOLE_HOST;
        return location != null && location.startsWith(origin + "/") ? location.substring(origin.length()) : location;
    }

    /** A local port that was free a moment ago and has nothing listening on it. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
