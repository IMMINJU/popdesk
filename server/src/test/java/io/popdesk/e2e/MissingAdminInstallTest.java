package io.popdesk.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.popdesk.e2e.support.Install;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An empty installation started without the first admin values (configuration.md
 * {@code POPDESK_ADMIN_EMAIL}), only with the database password that every installation sets
 * (secrets have no default, S0-AC-94): the process keeps running, its health check stays
 * unhealthy, and the log names what to set.
 */
@Tag("deploy")
class MissingAdminInstallTest {

    /** Long enough for the Compose health check's start period and retries to run out. */
    private static final Duration BECOMES_UNHEALTHY = Duration.ofMinutes(5);

    /** How long it must then stay unhealthy without restarting. */
    private static final Duration STAYS_UNHEALTHY = Duration.ofSeconds(30);

    private final Install install = new Install("popdesk-e2e-nosetup");

    @AfterEach
    void tearDown() {
        if (install.started()) {
            install.saveLog("app.log", install.logs("app"));
            install.downWithVolumes();
        }
    }

    @Test
    @DisplayName("S0-AC-94 an empty install without the first admin values stays unhealthy, keeps running, and logs the keys to set")
    void staysUnhealthyWithoutFirstAdmin() {
        Install.assertReadyToStart();
        install.up(Map.of("POPDESK_DB_PASSWORD", "Db-e2e-pass-5270"));
        String app = install.containerId("app");

        AtomicReference<String> last = new AtomicReference<>();
        try {
            Awaitility.await("app unhealthy").atMost(BECOMES_UNHEALTHY).pollInterval(Duration.ofSeconds(2))
                    .pollInSameThread().until(() -> {
                        String health = install.health(app);
                        last.set(health);
                        assertRunningAndNotHealthy(app, health);
                        return "unhealthy".equals(health);
                    });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("app did not become unhealthy within " + BECOMES_UNHEALTHY + " (last status: "
                    + last.get() + ")", e);
        }
        try {
            Awaitility.await("app stays unhealthy").during(STAYS_UNHEALTHY).atMost(STAYS_UNHEALTHY.plusSeconds(15))
                    .pollInterval(Duration.ofSeconds(2)).pollInSameThread().until(() -> {
                        String health = install.health(app);
                        assertRunningAndNotHealthy(app, health);
                        return "unhealthy".equals(health);
                    });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("app did not stay unhealthy for " + STAYS_UNHEALTHY + " (now: " + install.health(app) + ")", e);
        }

        assertThat(install.restartCount(app)).as("restarts of the app container").isZero();
        assertThat(install.containerId("app")).as("the app container is still the same one").isEqualTo(app);
        String log = install.logs("app");
        assertThat(log).as("the log says what to set").contains("POPDESK_ADMIN_EMAIL").contains("POPDESK_ADMIN_PASSWORD");
    }

    private void assertRunningAndNotHealthy(String app, String health) {
        assertThat(health).as("the app service has no health check in the Compose file").isNotNull();
        assertThat(health).as("health without the first admin values").isNotEqualTo("healthy");
        JsonNode state = install.state(app);
        assertThat(state.get("Running").asBoolean()).as("the app process keeps running (state %s, exit code %s)",
                state.get("Status").asString(), state.get("ExitCode").asString()).isTrue();
    }
}
