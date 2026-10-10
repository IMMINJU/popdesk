package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.List;

/**
 * A start with a {@code POPDESK_LOCALE} that is neither {@code en} nor {@code ko} (verify.md V-6). The
 * shape is that of a missing first admin (S0-AC-02): the process keeps running, readiness is down,
 * business requests get 503, and the log names the key.
 *
 * <p>Each test class uses one value.
 */
abstract class InvalidLocaleStart extends OwnStartTest {

    static final String KEY = "POPDESK_LOCALE";

    /** Starts a first install with {@code POPDESK_LOCALE=locale} and checks that it takes no requests. */
    static void assertFirstInstallTakesNoRequests(String locale) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, locale))) {
            assertTakesNoRequests(app, locale);
        }
    }

    /** Running, readiness down, liveness up, business requests 503, and a WARN or ERROR line naming the key. */
    static void assertTakesNoRequests(Boot app, String locale) {
        app.assertStarted();
        ApiResponse readiness = app.management("/actuator/health/readiness");
        assertThat(readiness.status()).as("readiness with %s=\"%s\": %s %s", KEY, locale, readiness,
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
