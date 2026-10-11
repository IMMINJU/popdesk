package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A start with a {@code POPDESK_AUDIT_RETENTION} the app does not accept: shorter than
 * {@code P30D}, or not an ISO-8601 duration. The shape is that of a missing first admin
 * (S0-AC-02): the process keeps running, readiness is down, business requests get 503, and the log
 * names the key.
 */
class AuditRetentionSettingTest extends OwnStartTest {

    private static final String KEY = "POPDESK_AUDIT_RETENTION";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"P29D", "PT1H", "PT719H59M59S", "365"})
    @DisplayName("S1B-AC-20 with a POPDESK_AUDIT_RETENTION shorter than P30D or not a duration, the app runs "
            + "but takes no business request: readiness DOWN, 503, and a log line naming the key")
    void retentionTooShortOrNotADurationTakesNoRequests(String value) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, value))) {
            app.assertStarted();
            ApiResponse readiness = app.management("/actuator/health/readiness");
            assertThat(readiness.status()).as("readiness with %s=\"%s\": %s %s", KEY, value, readiness,
                    readiness.snippet()).isEqualTo(503);
            assertThat(readiness.json().path("status").asString()).as("readiness status").isNotEqualTo("UP");
            ApiResponse liveness = app.management("/actuator/health/liveness");
            assertThat(liveness.status()).as(liveness + " " + liveness.snippet()).isEqualTo(200);

            for (ApiResponse business : List.of(app.console().get("/api/console/me").send(),
                    app.portal().get("/api/portal/me").send())) {
                assertThat(business.status()).as(business + " " + business.snippet()).isEqualTo(503);
                assertThat(business.errorCode()).as("code of " + business + " " + business.snippet()).isNotBlank();
            }

            assertThat(app.logLines()).as("a WARN or ERROR line naming %s", KEY)
                    .anyMatch(line -> line.contains(KEY) && (line.contains("WARN") || line.contains("ERROR")));
        }
    }

    @Test
    @DisplayName("S1B-AC-20 with POPDESK_AUDIT_RETENTION=P30D, the shortest period allowed, the app takes "
            + "requests: readiness UP and the first admin signs in")
    void shortestPeriodAllowedStarts() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, "P30D"))) {
            app.assertStarted();
            assertReady(app);
            ApiResponse signIn = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
            assertThat(signIn.status()).as(signIn + " " + signIn.snippet()).isEqualTo(204);
        }
    }
}
