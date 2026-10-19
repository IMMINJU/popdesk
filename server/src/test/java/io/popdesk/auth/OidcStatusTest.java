package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.OidcProvider.Redirect;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The {@code oidc} part of the system status with OIDC on for both apps (S11-AC-30): on, both apps, the time of the
 * last sign-in that started a session, and the provider-side failures. Other tests of this context sign in and fail
 * too, so a test reads the status right before and right after its own sign-in and sees only that it moved. Tests
 * of one JVM run one after another, so nothing else signs in between the two reads.
 */
class OidcStatusTest extends OidcAppTest {

    static final String STATUS = "/api/console/system/status";

    private static final String CONSOLE = "/api/console";

    static final Set<String> OIDC_FIELDS = Set.of("enabled", "apps", "lastSuccessAt", "providerFailures",
            "lastFailure");

    /** A provider-side failure the test provider can be made to give (S11-AC-13). */
    enum ProviderFailure {
        /** The token endpoint refuses the code: it is another sign-in's, with this sign-in's PKCE verifier. */
        TOKEN,
        /** The ID token is for another client. */
        ID_TOKEN
    }

    static JsonNode oidcStatus(Session admin) {
        return admin.client().get(STATUS).expect(200).json().path("oidc");
    }

    @Test
    @DisplayName("S11-AC-30 with OIDC on for both apps the status says enabled, apps CONSOLE and PORTAL, with exactly "
            + "the five fields, and a provider failure count that is a number")
    void statusSaysOnForBothApps() {
        Session admin = fixtures.signInAdmin();

        JsonNode status = oidcStatus(admin);

        assertThat(OidcFlow.fieldNames(status)).as("fields of %s", status).isEqualTo(OIDC_FIELDS);
        assertThat(status.path("enabled").isBoolean()).as("enabled of %s", status).isTrue();
        assertThat(status.path("enabled").booleanValue()).as("enabled of %s", status).isTrue();
        List<String> apps = new ArrayList<>();
        status.path("apps").forEach(app -> apps.add(app.asString("")));
        assertThat(apps).as("apps of %s", status).containsExactly("CONSOLE", "PORTAL");
        assertThat(status.path("providerFailures").isIntegralNumber()).as("providerFailures of %s", status)
                .isTrue();
    }

    @Test
    @DisplayName("S11-AC-30 an OIDC sign-in that starts a session sets lastSuccessAt to the app's time of it")
    void signInSetsLastSuccessAt() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        clock.advance(Duration.ofSeconds(7));
        Instant now = clock.instant();

        linkByOidc(agent);

        JsonNode status = oidcStatus(admin);
        assertThat(status.path("lastSuccessAt").isString()).as("lastSuccessAt of %s", status).isTrue();
        assertThat(OffsetDateTime.parse(status.path("lastSuccessAt").asString()).toInstant())
                .as("lastSuccessAt of %s", status).isEqualTo(now);
    }

    @Test
    @DisplayName("S11-AC-30 an OIDC sign-in that fails on the account side (no account with the email, an account "
            + "of the other app) changes neither the provider failure count nor the last failure")
    void accountSideFailuresAreNotCounted() {
        Session admin = fixtures.signInAdmin();
        Session customer = person(admin, Role.CUSTOMER);
        JsonNode before = oidcStatus(admin);

        for (Map<String, Object> claims : List.of(oidc.claims(Unique.email("nobody")),
                oidc.claims(customer.email()))) {
            ApiClient browser = console();
            OidcFlow.assertBackAtLogin(browser, oidc.signIn(browser, OidcFlow.newSub(), claims), "failed");
        }

        JsonNode after = oidcStatus(admin);
        assertThat(after.path("providerFailures").asLong(-1)).as("providerFailures after the failed sign-ins")
                .isEqualTo(before.path("providerFailures").asLong(-2));
        assertThat(after.path("lastFailure")).as("lastFailure after the failed sign-ins")
                .isEqualTo(before.path("lastFailure"));
    }

    @ParameterizedTest
    @EnumSource(ProviderFailure.class)
    @DisplayName("S11-AC-30 a code the token endpoint refuses, and an ID token for another client, go back to "
            + "/login?oidc=failed, add to the provider failure count, and make the last failure TOKEN or ID_TOKEN at "
            + "the app's time")
    void providerFailuresAreCounted(ProviderFailure failure) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        ApiClient browser = console();
        Map<String, Object> claims = oidc.claims(agent.email());
        Map<String, String> query;
        if (failure == ProviderFailure.TOKEN) {
            Redirect other = oidc.logIn(oidc.started(browser), OidcFlow.newSub(), claims);
            Started own = oidc.started(browser);
            Redirect back = oidc.logIn(own, OidcFlow.newSub(), claims);
            query = new LinkedHashMap<>(back.query());
            query.put("code", other.code());
        } else {
            claims.put("aud", "another-client");
            query = oidc.logIn(oidc.started(browser), OidcFlow.newSub(), claims).query();
        }
        clock.advance(Duration.ofSeconds(3));
        Instant now = clock.instant();
        long before = oidcStatus(admin).path("providerFailures").asLong(-1);

        OidcFlow.assertBackAtLogin(browser, oidc.callback(browser, query), "failed");

        OidcFlow.assertNoSession(browser, CONSOLE);
        JsonNode status = oidcStatus(admin);
        assertThat(status.path("providerFailures").asLong(-1)).as("providerFailures of %s", status)
                .isGreaterThan(before);
        JsonNode last = status.path("lastFailure");
        assertThat(OidcFlow.fieldNames(last)).as("fields of lastFailure %s", last).isEqualTo(Set.of("kind", "at"));
        assertThat(last.path("kind").asString("")).as("kind of %s", last).isEqualTo(failure.name());
        assertThat(OffsetDateTime.parse(last.path("at").asString()).toInstant()).as("at of %s", last)
                .isEqualTo(now);
        assertThat(linked(admin, agent.accountId())).as("oidcLinked after the failed sign-in").isFalse();
    }
}
