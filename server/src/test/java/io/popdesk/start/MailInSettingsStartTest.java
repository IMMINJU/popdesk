package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Mail-in settings the app refuses to start with (S12-AC-01): some of {@code IMAP_HOST}, {@code IMAP_USER},
 * {@code IMAP_PASSWORD}, and {@code MAIL_INBOUND_ADDRESS} without the others, an {@code IMAP_PORT} that is not a whole
 * number from 1 to 65535, a {@code MAIL_INBOUND_ADDRESS} that is not one address or holds a {@code +}, and an empty or
 * repeated folder. "Does not start" is read as in {@link Boot}; what the start told ({@link StorageSteps#told}) names
 * the key and never the IMAP password. Nothing listens on the IMAP port; the app must not need it to decide.
 */
class MailInSettingsStartTest extends OwnStartTest {

    private static final String PASSWORD = "rst-imap-start-secret-5b20e7";

    private static final List<String> KEYS = List.of("IMAP_HOST", "IMAP_USER", "IMAP_PASSWORD", "MAIL_INBOUND_ADDRESS");

    @ParameterizedTest(name = "set {0}, missing {1}")
    @CsvSource(delimiter = '|', value = {
        "IMAP_HOST|IMAP_USER IMAP_PASSWORD MAIL_INBOUND_ADDRESS",
        "IMAP_HOST IMAP_USER IMAP_PASSWORD|MAIL_INBOUND_ADDRESS",
        "IMAP_PASSWORD MAIL_INBOUND_ADDRESS|IMAP_HOST IMAP_USER"})
    @DisplayName("S12-AC-01 with some of IMAP_HOST, IMAP_USER, IMAP_PASSWORD, and MAIL_INBOUND_ADDRESS but not all, the app does not start, what it told names each missing key, and never the password")
    void someKeysOnly(String set, String missing) {
        Settings settings = Settings.firstInstall(postgres.newDatabase());
        for (String key : set.split(" ")) {
            settings.set(key, value(key));
        }
        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("only " + set + " of the mail-in keys are set");

            for (String key : missing.split(" ")) {
                assertThat(StorageSteps.linesWith(app, key)).as("what the start told about the missing %s", key)
                        .isNotEmpty();
            }
            assertThat(StorageSteps.told(app)).as("what the start told").doesNotContain(PASSWORD);
        }
    }

    @ParameterizedTest(name = "IMAP_PORT={0}")
    @ValueSource(strings = {"0", "70000", "abc"})
    @DisplayName("S12-AC-01 with an IMAP_PORT that is not a whole number from 1 to 65535 the app does not start, and what it told names IMAP_PORT and never the password")
    void portOutOfRange(String port) {
        assertRefused(allKeys().set("IMAP_PORT", port), "IMAP_PORT", "IMAP_PORT=" + port);
    }

    @ParameterizedTest(name = "MAIL_INBOUND_ADDRESS={0}")
    @ValueSource(strings = {"support@mail.example,help@mail.example", "support+acme@mail.example", "not-an-address"})
    @DisplayName("S12-AC-01 with a MAIL_INBOUND_ADDRESS that is two addresses, holds a +, or is no address the app does not start, and what it told names MAIL_INBOUND_ADDRESS and never the password")
    void inboundAddressNotOne(String address) {
        assertRefused(allKeys().set("MAIL_INBOUND_ADDRESS", address), "MAIL_INBOUND_ADDRESS",
                "MAIL_INBOUND_ADDRESS=" + address);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
        "IMAP_FOLDER|''",
        "IMAP_PROCESSED_FOLDER|''",
        "IMAP_PROCESSED_FOLDER|popdesk-in",
        "IMAP_QUARANTINE_FOLDER|popdesk-done"})
    @DisplayName("S12-AC-01 with an empty folder, or two of the three folders the same, the app does not start, and what it told names that key and never the password")
    void folderEmptyOrRepeated(String key, String folder) {
        Settings settings = allKeys().set("IMAP_FOLDER", "popdesk-in").set("IMAP_PROCESSED_FOLDER", "popdesk-done")
                .set("IMAP_QUARANTINE_FOLDER", "popdesk-held").set(key, folder);
        assertRefused(settings, key, key + "=" + folder);
    }

    private static void assertRefused(Settings settings, String key, String because) {
        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart(because);

            assertThat(StorageSteps.linesWith(app, key)).as("what the start told about %s", key).isNotEmpty();
            assertThat(StorageSteps.told(app)).as("what the start told").doesNotContain(PASSWORD);
        }
    }

    private static Settings allKeys() {
        Settings settings = Settings.firstInstall(postgres.newDatabase()).set("IMAP_ALLOW_PLAINTEXT", "true")
                .set("IMAP_PORT", "143");
        for (String key : KEYS) {
            settings.set(key, value(key));
        }
        return settings;
    }

    private static String value(String key) {
        return switch (key) {
            case "IMAP_HOST" -> "127.0.0.1";
            case "IMAP_USER" -> "start-support";
            case "IMAP_PASSWORD" -> PASSWORD;
            case "MAIL_INBOUND_ADDRESS" -> "support@mail.example";
            default -> throw new IllegalArgumentException("Not a mail-in key: " + key);
        };
    }
}
