package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * OIDC settings the app refuses to start with (S11-AC-01): some of {@code OIDC_ISSUER}, {@code OIDC_CLIENT_ID}, and
 * {@code OIDC_CLIENT_SECRET} without the others, an {@code OIDC_APPS} that is not {@code console}, {@code portal}, or
 * {@code both}, and an {@code OIDC_ISSUER} that is not an absolute http(s) address without query and fragment.
 * "Does not start" is read as in {@link Boot}; what the start told is its log and the messages of the failure
 * ({@link StorageSteps#told}). It names the key and never the client secret.
 */
class OidcSettingsStartTest extends OwnStartTest {

    private static final String ISSUER = "http://localhost:9/default";
    private static final String CLIENT_ID = "start-oidc-client";
    private static final String SECRET = "rst-oidc-start-secret-91c2a4";

    @ParameterizedTest(name = "set {0}, missing {1}")
    @CsvSource(delimiter = '|', value = {
        "OIDC_ISSUER|OIDC_CLIENT_ID OIDC_CLIENT_SECRET",
        "OIDC_ISSUER OIDC_CLIENT_ID|OIDC_CLIENT_SECRET",
        "OIDC_CLIENT_ID OIDC_CLIENT_SECRET|OIDC_ISSUER"})
    @DisplayName("S11-AC-01 with some of OIDC_ISSUER, OIDC_CLIENT_ID, and OIDC_CLIENT_SECRET but not all, the app does "
            + "not start, what it told names each missing key, and never the client secret")
    void someKeysOnlyEndTheStart(String set, String missing) {
        Settings settings = Settings.firstInstall(postgres.newDatabase());
        for (String key : set.split(" ")) {
            settings.set(key, value(key));
        }
        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("only " + set + " of the OIDC keys are set");

            for (String key : missing.split(" ")) {
                assertThat(StorageSteps.linesWith(app, key)).as("what the start told about the missing %s", key)
                        .isNotEmpty();
            }
            assertThat(StorageSteps.told(app)).as("what the start told").doesNotContain(SECRET);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"everyone", "console,portal"})
    @DisplayName("S11-AC-01 with an OIDC_APPS that is not console, portal, or both, the app does not start, what it "
            + "told names OIDC_APPS, and never the client secret")
    void unknownAppsEndTheStart(String apps) {
        Settings settings = allKeys().set("OIDC_APPS", apps);
        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("OIDC_APPS=" + apps);

            assertThat(StorageSteps.linesWith(app, "OIDC_APPS")).as("what the start told about OIDC_APPS")
                    .isNotEmpty();
            assertThat(StorageSteps.told(app)).as("what the start told").doesNotContain(SECRET);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http://localhost:9/default?tenant=a", "http://localhost:9/default#part", "/default"})
    @DisplayName("S11-AC-01 with an OIDC_ISSUER that has a query or a fragment or is not absolute, the app does not "
            + "start, what it told names OIDC_ISSUER, and never the client secret")
    void issuerOfTheWrongShapeEndsTheStart(String issuer) {
        Settings settings = allKeys().set("OIDC_ISSUER", issuer);
        try (Boot app = Boot.start(settings)) {
            app.assertDidNotStart("OIDC_ISSUER=" + issuer);

            assertThat(StorageSteps.linesWith(app, "OIDC_ISSUER")).as("what the start told about OIDC_ISSUER")
                    .isNotEmpty();
            assertThat(StorageSteps.told(app)).as("what the start told").doesNotContain(SECRET);
        }
    }

    private static Settings allKeys() {
        Settings settings = Settings.firstInstall(postgres.newDatabase());
        for (String key : List.of("OIDC_ISSUER", "OIDC_CLIENT_ID", "OIDC_CLIENT_SECRET")) {
            settings.set(key, value(key));
        }
        return settings;
    }

    private static String value(String key) {
        return switch (key) {
            case "OIDC_ISSUER" -> ISSUER;
            case "OIDC_CLIENT_ID" -> CLIENT_ID;
            case "OIDC_CLIENT_SECRET" -> SECRET;
            default -> throw new IllegalArgumentException("Not an OIDC key: " + key);
        };
    }
}
