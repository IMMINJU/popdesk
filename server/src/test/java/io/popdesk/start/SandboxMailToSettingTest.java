package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@code POPDESK_SANDBOX_MAIL_TO} that is no email address. In a sandbox the app does not start and
 * says which key is wrong without repeating its value, which could be most of a real address. Without
 * {@code POPDESK_SANDBOX=true} the key is not read, so the same value does no harm.
 */
class SandboxMailToSettingTest extends OwnStartTest {

    static final String KEY = "POPDESK_SANDBOX_MAIL_TO";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not-an-email-7f3a", "@no-local-part-7f3a.test"})
    @DisplayName("S4B-AC-06 in a sandbox with a POPDESK_SANDBOX_MAIL_TO that is no email address the app does "
            + "not start, and the log names the key and not its value")
    void sandboxWithABadAddressDoesNotStart(String value) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set("POPDESK_SANDBOX", "true").set(KEY, value))) {
            app.assertDidNotStart(KEY + " is no email address");
            List<String> told = new ArrayList<>(app.logLines().stream()
                    .filter(line -> line.contains("WARN") || line.contains("ERROR")).toList());
            for (Throwable t = app.failure(); t != null; t = t.getCause() == t ? null : t.getCause()) {
                told.add(String.valueOf(t.getMessage()));
            }
            assertThat(told).as("a WARN or ERROR line, or the reason the start failed, names %s", KEY)
                    .anyMatch(line -> line.contains(KEY));
            assertThat(told).as("the reasons the start failed").noneMatch(line -> line.contains(value));
            assertThat(app.log()).as("the log of the start").doesNotContain(value);
        }
    }

    @ParameterizedTest(name = "POPDESK_SANDBOX={0}")
    @ValueSource(strings = {"", "false"})
    @DisplayName("S4B-AC-06 without POPDESK_SANDBOX=true the same bad POPDESK_SANDBOX_MAIL_TO is not read: the "
            + "app starts, takes requests, and logs neither a complaint about the key nor its value")
    void notASandboxDoesNotReadTheKey(String sandbox) {
        String value = "not-an-email-7f3a";
        Database db = postgres.newDatabase();
        Settings settings = Settings.firstInstall(db).set(KEY, value);
        if (!sandbox.isEmpty()) {
            settings.set("POPDESK_SANDBOX", sandbox);
        }
        try (Boot app = Boot.start(settings)) {
            app.assertStarted();
            assertReady(app);
            ApiResponse signIn = app.signIn(Settings.ADMIN_EMAIL, Settings.ADMIN_PASSWORD);
            assertThat(signIn.status()).as(signIn + " " + signIn.snippet()).isEqualTo(204);
            assertThat(app.logLines()).as("WARN or ERROR lines naming %s", KEY)
                    .noneMatch(line -> line.contains(KEY) && (line.contains("WARN") || line.contains("ERROR")));
            assertThat(app.log()).as("the log of the start").doesNotContain(value);
        }
    }
}
