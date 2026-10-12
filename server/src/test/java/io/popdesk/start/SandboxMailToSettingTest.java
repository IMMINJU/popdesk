package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * A {@code POPDESK_SANDBOX_MAIL_TO} that is no email address. In a sandbox the app does not start and
 * says which key is wrong without repeating its value, which could be most of a real address. Without
 * {@code POPDESK_SANDBOX=true} the key is not read, so the same value does no harm.
 *
 * <p>A sandbox is {@code POPDESK_SANDBOX} set to {@code true} in any case, as the outside copy of the
 * audit log already reads it; {@code 1} and {@code yes} are no sandbox. Two addresses joined by a comma
 * are no address. An empty value is as no value: the app starts and mail is off.
 */
class SandboxMailToSettingTest extends OwnStartTest {

    static final String KEY = "POPDESK_SANDBOX_MAIL_TO";

    @ParameterizedTest(name = "POPDESK_SANDBOX={0} {1}")
    @CsvSource(delimiter = '|', value = {"true|not-an-email-7f3a", "true|@no-local-part-7f3a.test",
        "TRUE|not-an-email-7f3a", "true|first-7f3a@ops.example.test,second-7f3a@ops.example.test"})
    @DisplayName("S4B-AC-06 in a sandbox, POPDESK_SANDBOX=true in any case, with a POPDESK_SANDBOX_MAIL_TO that is "
            + "no single email address the app does not start, and the log names the key and not its value")
    void sandboxWithABadAddressDoesNotStart(String sandbox, String value) {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set("POPDESK_SANDBOX", sandbox).set(KEY, value))) {
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
            for (String part : value.split(",")) {
                assertThat(app.log()).as("the log of the start").doesNotContain(part);
            }
        }
    }

    @Test
    @DisplayName("S4B-AC-06 S4B-AC-03 in a sandbox an empty POPDESK_SANDBOX_MAIL_TO is as no address: the app "
            + "starts, and the status says sandbox, no address set, and mail off")
    void sandboxWithAnEmptyAddressStartsWithMailOff() {
        Database db = postgres.newDatabase();
        try (Boot app = Boot.start(Settings.firstInstall(db).set("POPDESK_SANDBOX", "true").set(KEY, ""))) {
            app.assertStarted();
            assertReady(app);
            ApiClient admin = app.console();
            admin.post("/api/public/sign-in")
                    .json(Map.of("email", Settings.ADMIN_EMAIL, "password", Settings.ADMIN_PASSWORD)).expect(204);

            JsonNode status = admin.get("/api/console/system/status").expect(200).json();

            assertThat(status.path("sandbox").isBoolean() && status.path("sandbox").booleanValue())
                    .as("sandbox is true in %s", status).isTrue();
            assertThat(status.path("sandboxMailToSet").isBoolean() && !status.path("sandboxMailToSet").booleanValue())
                    .as("sandboxMailToSet is false in %s", status).isTrue();
            assertThat(status.path("mail").path("enabled").isBoolean()
                    && !status.path("mail").path("enabled").booleanValue()).as("mail.enabled is false in %s", status)
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "POPDESK_SANDBOX={0}")
    @ValueSource(strings = {"", "false", "1", "yes"})
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
