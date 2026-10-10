package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POPDESK_TRUSTED_PROXIES=.:} (verify.md V-7): made only of characters an IPv6 address may
 * have, but neither an IP address nor a CIDR range. The app must not take it as a trusted address.
 *
 * <p>configuration.md does not say how a wrong value is refused, so either way counts: the app does
 * not start, or it runs with readiness down as for a wrong {@code POPDESK_LOCALE}. Either way the
 * operator is told the name of the key. Whether a name lookup happens is not checked here.
 *
 * <p>One value per class: each start is another app.
 */
class TrustedProxiesNotAnAddressTest extends OwnStartTest {

    static final String KEY = "POPDESK_TRUSTED_PROXIES";

    @Test
    @DisplayName("V-7 S0-AC-18 with POPDESK_TRUSTED_PROXIES=.: the app does not serve, and the key is named")
    void dotColonIsRefused() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, ".:"))) {
            if (app.started()) {
                ApiResponse readiness = app.management("/actuator/health/readiness");
                assertThat(readiness.status()).as("the app started with %s=.:, so readiness must be down: %s %s", KEY,
                        readiness, readiness.snippet()).isEqualTo(503);
                assertThat(app.logLines()).as("a WARN or ERROR line naming %s", KEY)
                        .anyMatch(line -> line.contains(KEY) && (line.contains("WARN") || line.contains("ERROR")));
            } else {
                List<String> told = new ArrayList<>(app.logLines().stream()
                        .filter(line -> line.contains("WARN") || line.contains("ERROR")).toList());
                for (Throwable t = app.failure(); t != null; t = t.getCause() == t ? null : t.getCause()) {
                    told.add(String.valueOf(t.getMessage()));
                }
                assertThat(told).as("a WARN or ERROR line, or the reason the start failed, names %s", KEY)
                        .anyMatch(line -> line.contains(KEY));
            }
        }
    }
}
