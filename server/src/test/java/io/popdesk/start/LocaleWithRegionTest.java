package io.popdesk.start;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code POPDESK_LOCALE=ko-KR}: a language tag with a region is not one of {@code en} and {@code ko}. */
class LocaleWithRegionTest extends InvalidLocaleStart {

    private static final String LOCALE = "ko-KR";

    @Test
    @DisplayName("V-6 S0-AC-02 with POPDESK_LOCALE=ko-KR the app runs, readiness DOWN, and the log names the key")
    void firstInstall() {
        assertFirstInstallTakesNoRequests(LOCALE);
    }

    @Test
    @DisplayName("V-6 with POPDESK_LOCALE=ko-KR an installation that already has its admin takes no requests either")
    void installationWithAnAdmin() {
        Database db = postgres.newDatabase();
        try (Boot first = Boot.start(Settings.firstInstall(db))) {
            first.assertStarted();
            assertReady(first);
        }

        try (Boot app = Boot.start(Settings.firstInstall(db).set(KEY, LOCALE))) {
            assertTakesNoRequests(app, LOCALE);
        }
    }
}
