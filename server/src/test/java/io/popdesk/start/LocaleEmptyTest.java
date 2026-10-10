package io.popdesk.start;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code POPDESK_LOCALE} set to nothing, as a line {@code POPDESK_LOCALE=} in an {@code .env} file gives it. */
class LocaleEmptyTest extends InvalidLocaleStart {

    @Test
    @DisplayName("V-6 S0-AC-02 with an empty POPDESK_LOCALE the app runs, readiness DOWN, and the log names the key")
    void firstInstall() {
        assertFirstInstallTakesNoRequests("");
    }
}
