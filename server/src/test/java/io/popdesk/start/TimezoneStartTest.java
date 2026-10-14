package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Starts with a {@code POPDESK_TIMEZONE} popdesk takes or does not take. A value it does not take has
 * the shape of a wrong {@code POPDESK_LOCALE} ({@link InvalidLocaleStart}): the process keeps running,
 * readiness is down, business requests get 503 {@code SETUP_REQUIRED}, and the log names the key and
 * says what it takes. Each test starts one first install on a new database.
 */
class TimezoneStartTest extends OwnStartTest {

    static final String KEY = "POPDESK_TIMEZONE";

    @ParameterizedTest(name = "POPDESK_TIMEZONE=\"{0}\"")
    @ValueSource(strings = {"", "KST", "+09:00", "GMT+9", "Asia/Nowhere"})
    @DisplayName("S6A-AC-70 with an empty POPDESK_TIMEZONE, an abbreviation, an offset, or a zone that does not "
            + "exist the app runs but takes no requests: readiness down, 503 SETUP_REQUIRED, and a log line naming "
            + "the key and UTC among what it takes")
    void valueNotTaken(String zone) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, zone))) {
            app.assertStarted();
            ApiResponse readiness = app.management("/actuator/health/readiness");
            assertThat(readiness.status()).as("readiness with %s=\"%s\": %s %s", KEY, zone, readiness,
                    readiness.snippet()).isEqualTo(503);
            assertThat(readiness.json().path("status").asString()).as("readiness status").isNotEqualTo("UP");
            ApiResponse liveness = app.management("/actuator/health/liveness");
            assertThat(liveness.status()).as(liveness + " " + liveness.snippet()).isEqualTo(200);
            for (ApiResponse business : List.of(app.console().get("/api/console/me").send(),
                    app.portal().get("/api/portal/me").send())) {
                assertThat(business.status()).as(business + " " + business.snippet()).isEqualTo(503);
                assertThat(business.errorCode()).as("code of " + business).isEqualTo("SETUP_REQUIRED");
            }
            assertThat(app.logLines()).as("a WARN or ERROR line naming %s and UTC", KEY)
                    .anyMatch(line -> line.contains(KEY) && line.contains("UTC")
                            && (line.contains("WARN") || line.contains("ERROR")));
        }
    }

    @Test
    @DisplayName("S6A-AC-71 with POPDESK_TIMEZONE=UTC the app is ready and answers requests")
    void utcIsTaken() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, "UTC"))) {
            app.assertStarted();
            assertReady(app);
            ApiResponse me = app.console().get("/api/console/me").send();
            assertThat(me.status()).as(me + " " + me.snippet()).isEqualTo(401);
            assertThat(app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD).status()).as("sign-in of the first admin")
                    .isEqualTo(204);
        }
    }
}
