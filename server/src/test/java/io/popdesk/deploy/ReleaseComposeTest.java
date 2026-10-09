package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.ComposeModel.Port;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The release Compose file, {@code deploy/compose.yml}, against the Compose contract (ADR D-45,
 * D-46, {@code docs/operate/configuration.md}). Every check runs twice: on the file as written and
 * on what {@code docker compose config} makes of it. Nothing is started.
 */
@Tag("deploy")
class ReleaseComposeTest {

    private static final String EXACT_POSTGRES_16 = "postgres:16\\.\\d+(-[a-z0-9.]+)?(@sha256:[0-9a-f]{64})?";
    private static final String EXACT_VERSION_TAG = "[^:@]+:v?\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.]+)?(@sha256:[0-9a-f]{64})?"
            + "|[^@]+@sha256:[0-9a-f]{64}";

    private static ComposeModel model(String how) {
        return switch (how) {
            case "file" -> ComposeModel.read(Repo.releaseCompose());
            case "config" -> {
                try (Stack stack = Stack.of("compose", Repo.releaseCompose())) {
                    yield ComposeModel.resolved(stack.config());
                }
            }
            default -> throw new IllegalArgumentException(how);
        };
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] services app and db only")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 the release file has the services app and db, and no others")
    void appAndDb(String how) {
        ComposeModel compose = model(how);

        assertThat(compose.services().keySet()).as("services in %s", compose.origin())
                .containsExactlyInAnyOrder("app", "db");
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] db is PostgreSQL 16 at an exact version")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 db runs PostgreSQL 16 at an exact minor version")
    void postgresSixteen(String how) {
        ComposeModel compose = model(how);

        assertThat(compose.image("db")).as("db image in %s", compose.origin()).matches(EXACT_POSTGRES_16);
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] only app 8080, on all interfaces")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 only app 8080 is published, on all interfaces; 8081 and 5432 are not")
    void onlyAppPort(String how) {
        ComposeModel compose = model(how);

        List<Port> appPorts = compose.ports("app");
        assertThat(appPorts).as("app ports in %s", compose.origin()).hasSize(1);
        Port port = appPorts.getFirst();
        assertThat(port.target()).as("app container port").isEqualTo(8080);
        assertThat(port.published()).as("app host port").isEqualTo("8080");
        assertThat(port.hostIp()).as("app host address (none: all interfaces)").isNull();
        assertThat(compose.ports("db")).as("db ports in %s", compose.origin()).isEmpty();
        List<Integer> published = new ArrayList<>();
        for (String service : compose.services().keySet()) {
            compose.ports(service).forEach(p -> published.add(p.target()));
        }
        assertThat(published).as("published container ports").doesNotContain(8081, 5432);
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] volumes popdesk-db and popdesk-attachments by name")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 the volumes are named popdesk-db and popdesk-attachments in the file, and are mounted")
    void namedVolumes(String how) {
        ComposeModel compose = model(how);

        String dbKey = volumeKeyNamed(compose, "popdesk-db");
        String attachmentsKey = volumeKeyNamed(compose, "popdesk-attachments");
        assertThat(compose.mounts("db")).as("db mounts in %s", compose.origin())
                .anyMatch(m -> dbKey.equals(m.source()) && m.target().equals("/var/lib/postgresql/data"));
        assertThat(compose.mounts("app")).as("app mounts in %s", compose.origin())
                .anyMatch(m -> attachmentsKey.equals(m.source()) && m.target().equals("/data/attachments"));
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] restart unless-stopped")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 both services restart unless stopped")
    void restartPolicy(String how) {
        ComposeModel compose = model(how);

        for (String service : List.of("app", "db")) {
            assertThat(compose.service(service).get("restart")).as("restart of %s in %s", service, compose.origin())
                    .isEqualTo("unless-stopped");
        }
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] log size limit 10m x 5")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 both services limit their log to max-size 10m and max-file 5")
    void logLimit(String how) {
        ComposeModel compose = model(how);

        for (String service : List.of("app", "db")) {
            Map<String, Object> logging = ComposeModel.map(compose.service(service).get("logging"));
            Object driver = logging.get("driver");
            assertThat(driver == null || driver.equals("json-file") || driver.equals("local"))
                    .as("log driver %s of %s keeps files on the host", driver, service).isTrue();
            Map<String, Object> options = ComposeModel.map(logging.get("options"));
            assertThat(options.get("max-size")).as("max-size of %s in %s", service, compose.origin()).isEqualTo("10m");
            assertThat(options.get("max-file")).as("max-file of %s in %s (a string)", service, compose.origin())
                    .isEqualTo("5");
        }
    }

    @ParameterizedTest(name = "S0-AC-88 [{0}] exact image versions, no latest")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("S0-AC-88 every image names an exact version; none is latest or untagged")
    void exactImages(String how) {
        ComposeModel compose = model(how);

        for (String service : compose.services().keySet()) {
            String image = compose.image(service);
            assertThat(image).as("image of %s in %s", service, compose.origin()).isNotBlank();
            assertThat(image).as("image of %s", service).doesNotContain(":latest").contains(":");
        }
        assertThat(compose.image("app")).as("app image in %s", compose.origin()).matches(EXACT_VERSION_TAG);
    }

    private static String volumeKeyNamed(ComposeModel compose, String name) {
        for (Map.Entry<String, Object> volume : compose.volumes().entrySet()) {
            Object fixed = ComposeModel.map(volume.getValue()).get("name");
            if (name.equals(fixed)) {
                return volume.getKey();
            }
        }
        throw new AssertionError("No top-level volume with `name: " + name + "` in " + compose.origin() + ": "
                + compose.volumes());
    }
}
