package io.popdesk.helpdesk.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.PublicAddress.App;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The OIDC settings (S11-AC-01; configuration.md "OIDC sign-in"; conventions.md "OIDC sign-in"): all of
 * {@code OIDC_ISSUER}, {@code OIDC_CLIENT_ID}, {@code OIDC_CLIENT_SECRET} or none; {@code OIDC_APPS} is
 * {@code console}, {@code portal}, or {@code both} ({@code console} when left out); the issuer is an absolute
 * {@code http(s)} address without a query or a fragment, kept as written, and the discovery document is read from
 * it without its last {@code /}. A problem names its key and its reason and never holds the secret.
 */
class OidcSettingsTest {

    private static final String ISSUER = "https://id.example/realms/popdesk";
    private static final String CLIENT_ID = "popdesk-client";
    private static final String SECRET = "s3cr3t-value-never-logged";

    private static OidcSettings read(String issuer, String clientId, String secret, String apps) {
        Map<String, String> values = new HashMap<>();
        values.put("OIDC_ISSUER", issuer);
        values.put("OIDC_CLIENT_ID", clientId);
        values.put("OIDC_CLIENT_SECRET", secret);
        values.put("OIDC_APPS", apps);
        return OidcSettings.read(values::get);
    }

    private static OidcSettings full(String apps) {
        return read(ISSUER, CLIENT_ID, SECRET, apps);
    }

    private static OidcSettings withIssuer(String issuer) {
        return read(issuer, CLIENT_ID, SECRET, null);
    }

    @Test
    @DisplayName("S11-AC-01 none of the three keys: OIDC is off and the app starts")
    void off() {
        OidcSettings settings = read(null, null, null, null);

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.apps()).isEmpty();
        assertThat(settings.on(App.CONSOLE)).isFalse();
        assertThat(settings.on(App.PORTAL)).isFalse();
        assertThat(settings.issuer()).isNull();
        assertThat(settings.discoveryUri()).isNull();
        assertThat(settings.httpIssuer()).isFalse();
        assertThat(settings.httpsIssuer()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 blank values are the same as keys not set: OIDC is off")
    void blankIsOff() {
        OidcSettings settings = read(" ", "", "  ", null);

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.apps()).isEmpty();
    }

    @Test
    @DisplayName("S11-AC-01 all three keys and no OIDC_APPS: on for the console only")
    void consoleByDefault() {
        OidcSettings settings = full(null);

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.apps()).containsExactly(App.CONSOLE);
        assertThat(settings.on(App.CONSOLE)).isTrue();
        assertThat(settings.on(App.PORTAL)).isFalse();
        assertThat(settings.issuer()).isEqualTo(ISSUER);
        assertThat(settings.clientId()).isEqualTo(CLIENT_ID);
        assertThat(settings.clientSecret()).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("S11-AC-01 OIDC_APPS console")
    void console() {
        OidcSettings settings = full("console");

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.apps()).containsExactly(App.CONSOLE);
    }

    @Test
    @DisplayName("S11-AC-01 OIDC_APPS portal: on for the portal only")
    void portal() {
        OidcSettings settings = full("portal");

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.apps()).containsExactly(App.PORTAL);
        assertThat(settings.on(App.PORTAL)).isTrue();
        assertThat(settings.on(App.CONSOLE)).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 OIDC_APPS both: on for both, CONSOLE before PORTAL")
    void both() {
        OidcSettings settings = full("both");

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.apps()).containsExactly(App.CONSOLE, App.PORTAL);
        assertThat(settings.on(App.CONSOLE)).isTrue();
        assertThat(settings.on(App.PORTAL)).isTrue();
    }

    @Test
    @DisplayName("S11-AC-01 OIDC_APPS in another letter case and with spaces around it is taken")
    void appsCaseAndSpaces() {
        assertThat(full(" BOTH ").apps()).containsExactly(App.CONSOLE, App.PORTAL);
        assertThat(full("Portal").apps()).containsExactly(App.PORTAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "none", "console,portal", "consoles", "agent", "true"})
    @DisplayName("S11-AC-01 OIDC_APPS other than console, portal, or both stops the start, naming the key")
    void badApps(String apps) {
        OidcSettings settings = full(apps);

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).contains("OIDC_APPS").doesNotContain(SECRET);
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 a bad OIDC_APPS stops the start while OIDC is off too")
    void badAppsWhileOff() {
        OidcSettings settings = read(null, null, null, "everything");

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).contains("OIDC_APPS");
    }

    @Test
    @DisplayName("S11-AC-01 only the issuer: the client ID and the secret are named as missing")
    void onlyIssuer() {
        OidcSettings settings = read(ISSUER, null, null, null);

        assertThat(settings.problems()).hasSize(2);
        assertThat(String.join("\n", settings.problems())).contains("OIDC_CLIENT_ID is not set",
                "OIDC_CLIENT_SECRET is not set");
        assertThat(settings.problems()).noneMatch(p -> p.startsWith("OIDC_ISSUER"));
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 only the secret: the issuer and the client ID are named, the secret is not logged")
    void onlySecret() {
        OidcSettings settings = read(null, null, SECRET, null);

        assertThat(settings.problems()).hasSize(2);
        assertThat(String.join("\n", settings.problems())).contains("OIDC_ISSUER is not set",
                "OIDC_CLIENT_ID is not set").doesNotContain(SECRET);
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 the issuer and the client ID without the secret: the secret is named")
    void noSecret() {
        OidcSettings settings = read(ISSUER, CLIENT_ID, null, "both");

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).startsWith("OIDC_CLIENT_SECRET is not set");
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.on(App.CONSOLE)).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 the client ID and the secret without the issuer: the issuer is named")
    void noIssuer() {
        OidcSettings settings = read(null, CLIENT_ID, SECRET, null);

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).startsWith("OIDC_ISSUER is not set").doesNotContain(SECRET);
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 the issuer and the secret without the client ID: the client ID is named")
    void noClientId() {
        OidcSettings settings = read(ISSUER, null, SECRET, null);

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).startsWith("OIDC_CLIENT_ID is not set").doesNotContain(SECRET);
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 a blank secret counts as missing")
    void blankSecret() {
        OidcSettings settings = read(ISSUER, CLIENT_ID, "   ", null);

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).startsWith("OIDC_CLIENT_SECRET is not set");
    }

    @Test
    @DisplayName("S11-AC-01 the client ID alone: the issuer and the secret are named")
    void onlyClientId() {
        OidcSettings settings = read(null, CLIENT_ID, null, null);

        assertThat(settings.problems()).hasSize(2);
        assertThat(String.join("\n", settings.problems())).contains("OIDC_ISSUER is not set",
                "OIDC_CLIENT_SECRET is not set");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "id.example", "/realms/popdesk", "ftp://id.example", "file:///etc/passwd", "ldap://id.example",
        "https://id.example/?x=1", "https://id.example/realms?", "https://id.example/#f", "https://id.example#",
        "https:///realms", "https:id.example", "https://id example", "mailto:a@id.example"})
    @DisplayName("S11-AC-01 an issuer that is not an absolute http(s) address without a query or a fragment stops "
            + "the start, naming the key")
    void badIssuer(String issuer) {
        OidcSettings settings = withIssuer(issuer);

        assertThat(settings.problems()).hasSize(1);
        assertThat(settings.problems().get(0)).startsWith("OIDC_ISSUER").doesNotContain(SECRET);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.discoveryUri()).isNull();
    }

    @Test
    @DisplayName("S11-AC-01 no problem holds the secret, whatever else is wrong")
    void secretNeverInProblems() {
        OidcSettings settings = read("ftp://x", null, SECRET, "many");

        assertThat(settings.problems()).hasSize(3);
        assertThat(settings.problems()).noneMatch(p -> p.contains(SECRET));
        assertThat(settings.toString()).doesNotContain(SECRET);
        assertThat(full(null).toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("S11-AC-01 the issuer is kept as written, with its last /, and the discovery address cuts it")
    void trailingSlash() {
        OidcSettings settings = withIssuer("https://id.example/realms/popdesk/");

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.issuer()).isEqualTo("https://id.example/realms/popdesk/");
        assertThat(settings.discoveryUri())
                .isEqualTo(URI.create("https://id.example/realms/popdesk/.well-known/openid-configuration"));
    }

    @Test
    @DisplayName("S11-AC-01 without a last / the discovery address is the same")
    void noTrailingSlash() {
        OidcSettings settings = withIssuer(ISSUER);

        assertThat(settings.issuer()).isEqualTo(ISSUER);
        assertThat(settings.discoveryUri())
                .isEqualTo(URI.create("https://id.example/realms/popdesk/.well-known/openid-configuration"));
    }

    @Test
    @DisplayName("S11-AC-01 a host-only issuer with and without its last / reads the same discovery address")
    void hostOnly() {
        URI expected = URI.create("https://id.example/.well-known/openid-configuration");

        assertThat(withIssuer("https://id.example").discoveryUri()).isEqualTo(expected);
        assertThat(withIssuer("https://id.example/").discoveryUri()).isEqualTo(expected);
        assertThat(withIssuer("https://id.example/").issuer()).isEqualTo("https://id.example/");
    }

    @Test
    @DisplayName("S11-AC-01 an https issuer: https, not http")
    void httpsIssuer() {
        OidcSettings settings = withIssuer("https://id.example");

        assertThat(settings.httpsIssuer()).isTrue();
        assertThat(settings.httpIssuer()).isFalse();
    }

    @Test
    @DisplayName("S11-AC-01 an http issuer with a port is taken: http, not https")
    void httpIssuer() {
        OidcSettings settings = withIssuer("http://localhost:8080/default");

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.httpIssuer()).isTrue();
        assertThat(settings.httpsIssuer()).isFalse();
        assertThat(settings.discoveryUri())
                .isEqualTo(URI.create("http://localhost:8080/default/.well-known/openid-configuration"));
    }

    @Test
    @DisplayName("S11-AC-01 spaces around the issuer are cut")
    void issuerSpaces() {
        OidcSettings settings = withIssuer("  " + ISSUER + " ");

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.issuer()).isEqualTo(ISSUER);
    }
}
