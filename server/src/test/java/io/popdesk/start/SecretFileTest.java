package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Secrets read from {@code <NAME>_FILE}, shown with {@code POPDESK_ADMIN_PASSWORD_FILE}: the value
 * the app read is the password the first admin signs in with.
 */
class SecretFileTest extends OwnStartTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("S0-AC-04 POPDESK_ADMIN_PASSWORD_FILE is read, and one \\n at the end is cut")
    void fileWithNewline() {
        assertFilePassword("File-Password-1\n", "File-Password-1", "File-Password-1\n");
    }

    @Test
    @DisplayName("S0-AC-04 one \\r\\n at the end of a secret file is cut")
    void fileWithCrLf() {
        assertFilePassword("File-Password-2\r\n", "File-Password-2", "File-Password-2\r");
    }

    @Test
    @DisplayName("S0-AC-04 a second line break and spaces around the value stay in it")
    void fileKeepsOtherWhitespace() {
        assertFilePassword("  File-Password-3 \n\n", "  File-Password-3 \n", "File-Password-3");
    }

    @Test
    @DisplayName("S0-AC-04 POPDESK_ADMIN_PASSWORD and POPDESK_ADMIN_PASSWORD_FILE together stop the start with the reason")
    void bothSet() {
        Database db = postgres.newDatabase();
        Path file = write("both", "File-Password-4\n");
        Settings settings = Settings.firstInstall(db).set("POPDESK_ADMIN_PASSWORD_FILE", file.toString());

        Boot app = Boot.start(settings);
        app.assertDidNotStart("both POPDESK_ADMIN_PASSWORD and POPDESK_ADMIN_PASSWORD_FILE are set");

        assertThat(app.logLines()).as("a line naming POPDESK_ADMIN_PASSWORD_FILE")
                .anyMatch(line -> line.contains("POPDESK_ADMIN_PASSWORD_FILE"));
        assertThat(app.log()).as("the log").doesNotContain(Settings.ADMIN_PASSWORD).doesNotContain("File-Password-4");
        assertThat(db.accountRows()).as("account rows").isEmpty();
    }

    /** Starts with only the file, then signs in with what the app should have read and with a near miss. */
    private void assertFilePassword(String fileContent, String password, String nearMiss) {
        Database db = postgres.newDatabase();
        Path file = write("admin_password", fileContent);
        Settings settings = Settings.firstInstall(db)
                .without("POPDESK_ADMIN_PASSWORD")
                .set("POPDESK_ADMIN_PASSWORD_FILE", file.toString());

        try (Boot app = Boot.start(settings)) {
            app.assertStarted();

            ApiResponse right = app.signIn(Settings.ADMIN_EMAIL, password);
            assertThat(right.status()).as("sign-in with %s: %s %s", visible(password), right, right.snippet())
                    .isEqualTo(204);
            ApiResponse wrong = app.signIn(Settings.ADMIN_EMAIL, nearMiss);
            assertThat(wrong.status()).as("sign-in with %s: %s", visible(nearMiss), wrong).isEqualTo(401);
        }
    }

    private Path write(String name, String content) {
        try {
            return Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String visible(String value) {
        return "'" + value.replace("\r", "\\r").replace("\n", "\\n") + "'";
    }
}
