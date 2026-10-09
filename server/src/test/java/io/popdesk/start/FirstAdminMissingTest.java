package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An empty database and first admin values that cannot make an admin. The process keeps running
 * with readiness DOWN, so a container is not restarted again and again; business requests get 503
 * {@code SETUP_REQUIRED} (conventions); the log names the keys to set.
 */
class FirstAdminMissingTest extends OwnStartTest {

    @Test
    @DisplayName("S0-AC-02 without POPDESK_ADMIN_EMAIL the app runs, readiness DOWN, and the log names the key")
    void emailMissing() {
        Database db = postgres.newDatabase();
        assertWaitsForSetup(Settings.firstInstall(db).without("POPDESK_ADMIN_EMAIL"), db,
                List.of("POPDESK_ADMIN_EMAIL"), Settings.ADMIN_PASSWORD);
    }

    @Test
    @DisplayName("S0-AC-02 without POPDESK_ADMIN_PASSWORD the app runs, readiness DOWN, and the log names the key")
    void passwordMissing() {
        Database db = postgres.newDatabase();
        assertWaitsForSetup(Settings.firstInstall(db).without("POPDESK_ADMIN_PASSWORD"), db,
                List.of("POPDESK_ADMIN_PASSWORD"), null);
    }

    @Test
    @DisplayName("S0-AC-02 without both first admin values the app runs, readiness DOWN, and the log names both")
    void bothMissing() {
        Database db = postgres.newDatabase();
        assertWaitsForSetup(Settings.firstInstall(db).without("POPDESK_ADMIN_EMAIL").without("POPDESK_ADMIN_PASSWORD"),
                db, List.of("POPDESK_ADMIN_EMAIL", "POPDESK_ADMIN_PASSWORD"), null);
    }

    @Test
    @DisplayName("S0-AC-02 a first admin password against AUTH-5 keeps the app waiting, without the password in the log")
    void passwordAgainstRules() {
        Database db = postgres.newDatabase();
        String nineCharacters = "Nine-ch1x";
        assertWaitsForSetup(Settings.firstInstall(db).set("POPDESK_ADMIN_PASSWORD", nineCharacters), db,
                List.of("POPDESK_ADMIN_PASSWORD"), nineCharacters);
    }

    private static void assertWaitsForSetup(Settings settings, Database db, List<String> keys, String secret) {
        try (Boot app = Boot.start(settings)) {
            app.assertStarted();

            ApiResponse readiness = app.management("/actuator/health/readiness");
            assertThat(readiness.status()).as(readiness + " " + readiness.snippet()).isEqualTo(503);
            assertThat(readiness.json().path("status").asString()).as("readiness status").isEqualTo("DOWN");
            ApiResponse liveness = app.management("/actuator/health/liveness");
            assertThat(liveness.status()).as(liveness + " " + liveness.snippet()).isEqualTo(200);

            for (ApiResponse business : List.of(app.console().get("/api/console/me").send(),
                    app.portal().get("/api/portal/me").send())) {
                assertThat(business.status()).as(business + " " + business.snippet()).isEqualTo(503);
                assertThat(business.errorCode()).as(business.toString()).isEqualTo("SETUP_REQUIRED");
            }

            assertThat(db.accountRows()).as("account rows").isEmpty();
            assertThat(db.firstAdminAuditRows()).as("FIRST_ADMIN_CREATED rows").isEmpty();

            for (String key : keys) {
                assertThat(app.logLines()).as("a WARN or ERROR line naming %s", key)
                        .anyMatch(line -> line.contains(key) && (line.contains("WARN") || line.contains("ERROR")));
            }
            if (secret != null) {
                assertThat(app.log()).as("the log").doesNotContain(secret);
            }
        }
    }
}
