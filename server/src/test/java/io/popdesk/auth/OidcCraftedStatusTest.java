package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.OidcFlow.Started;
import io.popdesk.support.ApiClient;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@code oidc} part of the system status with OIDC on for the console only (S11-AC-30): the apps, and the
 * provider failures the test container cannot give (the discovery document, the token endpoint). A test reads the
 * status right before and right after its own failure; tests of one JVM run one after another.
 */
class OidcCraftedStatusTest extends OidcCraftedAppTest {

    @Test
    @DisplayName("S11-AC-30 with OIDC_APPS=console the status says enabled and apps CONSOLE only")
    void statusSaysOnForTheConsoleOnly() {
        Session admin = fixtures.signInAdmin();

        JsonNode status = oidcStatus(admin);

        assertThat(status.path("enabled").isBoolean() && status.path("enabled").booleanValue())
                .as("enabled of %s", status).isTrue();
        List<String> apps = new ArrayList<>();
        status.path("apps").forEach(app -> apps.add(app.asString("")));
        assertThat(apps).as("apps of %s", status).containsExactly("CONSOLE");
    }

    @Test
    @DisplayName("S11-AC-30 a failed discovery adds one provider failure and makes the last failure DISCOVERY at the "
            + "app's time; a start refused from the remembered failure, without a request, changes neither")
    void discoveryFailureIsCountedOnce() {
        Session admin = fixtures.signInAdmin();
        forgetDiscovery();
        provider.discoveryAnswers(500);
        try {
            clock.advance(Duration.ofSeconds(3));
            Instant failedAt = clock.instant();
            long before = oidcStatus(admin).path("providerFailures").asLong(-1);

            ApiClient browser = console();
            OidcFlow.assertBackAtLogin(browser, oidc.start(browser), "unavailable");

            JsonNode afterFailure = oidcStatus(admin);
            assertThat(afterFailure.path("providerFailures").asLong(-1)).as("providerFailures of %s", afterFailure)
                    .isEqualTo(before + 1);
            assertLastFailure(afterFailure, "DISCOVERY", failedAt);
            clock.advance(Duration.ofSeconds(10));
            ApiClient refused = console();
            OidcFlow.assertBackAtLogin(refused, oidc.start(refused), "unavailable");
            JsonNode afterRefused = oidcStatus(admin);
            assertThat(afterRefused.path("providerFailures").asLong(-1))
                    .as("providerFailures after the start refused from the remembered failure: %s", afterRefused)
                    .isEqualTo(before + 1);
            assertLastFailure(afterRefused, "DISCOVERY", failedAt);
        } finally {
            provider.reset();
            clock.advance(Duration.ofSeconds(31));
        }
    }

    @Test
    @DisplayName("S11-AC-30 a token endpoint answering 503 adds one provider failure and makes the last failure TOKEN "
            + "at the app's time")
    void tokenFailureIsCounted() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();
        Started started = started(browser);
        String token = provider.rs256(claims(started, OidcFlow.newSub(), agent.email()));
        clock.advance(Duration.ofSeconds(3));
        Instant failedAt = clock.instant();
        long before = oidcStatus(admin).path("providerFailures").asLong(-1);
        provider.tokenAnswers(503);

        OidcFlow.assertBackAtLogin(browser, back(browser, started, token), "unavailable");

        provider.reset();
        JsonNode status = oidcStatus(admin);
        assertThat(status.path("providerFailures").asLong(-1)).as("providerFailures of %s", status)
                .isEqualTo(before + 1);
        assertLastFailure(status, "TOKEN", failedAt);
    }

    private static void assertLastFailure(JsonNode status, String kind, Instant at) {
        JsonNode last = status.path("lastFailure");
        assertThat(OidcFlow.fieldNames(last)).as("fields of lastFailure %s", last).isEqualTo(Set.of("kind", "at"));
        assertThat(last.path("kind").asString("")).as("kind of %s", last).isEqualTo(kind);
        assertThat(OffsetDateTime.parse(last.path("at").asString()).toInstant()).as("at of %s", last).isEqualTo(at);
    }
}
