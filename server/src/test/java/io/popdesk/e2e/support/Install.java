package io.popdesk.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import tools.jackson.databind.JsonNode;

/**
 * One popdesk installation from the release Compose file, as an operator runs it.
 *
 * <p>The release file is used as it is. An override file replaces only the {@code app} image.
 * Settings go in a {@code .env} file in the project directory, where an operator keeps them;
 * Compose reads it both for {@code ${...}} substitution and for {@code env_file: .env}.
 */
public final class Install {

    /** Volume names are fixed in the Compose file (configuration.md), so every project shares them. */
    public static final List<String> VOLUMES = List.of("popdesk-db", "popdesk-attachments");

    private final String project;
    private final Path dir;
    private boolean started;

    public Install(String project) {
        this.project = project;
        this.dir = Settings.workDir(project);
    }

    public Path dir() {
        return dir;
    }

    public boolean started() {
        return started;
    }

    /**
     * Fails (an assertion) when something the run needs is missing: Docker, the image, the release
     * Compose file, or empty volumes. Lists every problem at once.
     */
    public static void assertReadyToStart() {
        List<String> problems = new ArrayList<>();
        Docker.Result info = Docker.run("info", "--format", "{{.ServerVersion}}");
        if (!info.ok()) {
            problems.add("Docker is not reachable: " + Docker.tail(info.output(), 5));
        } else {
            if (!Docker.run("image", "inspect", Settings.image()).ok()) {
                problems.add("image `" + Settings.image() + "` does not exist; build it in the popdesk root with "
                        + "`docker build -t popdesk:test .`, or set POPDESK_TEST_IMAGE");
            }
            for (String volume : VOLUMES) {
                if (Docker.run("volume", "inspect", volume).ok()) {
                    problems.add("volume `" + volume + "` already exists, so the database would not be empty; "
                            + "another installation is running or was left behind (`docker volume rm " + volume + "`)");
                }
            }
        }
        if (!Files.isRegularFile(Settings.releaseCompose())) {
            problems.add("release Compose file `" + Settings.releaseCompose() + "` does not exist (set POPDESK_REPO)");
        }
        assertThat(problems).as("cannot start an empty installation").isEmpty();
    }

    /** Writes the settings and starts, or reconfigures, the installation with {@code up -d}. */
    public void up(Map<String, String> settings) {
        try {
            Files.createDirectories(dir);
            StringBuilder env = new StringBuilder();
            settings.forEach((k, v) -> env.append(k).append('=').append(v).append('\n'));
            Files.writeString(dir.resolve(".env"), env, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("image-override.yml"),
                    "services:\n  app:\n    image: " + Settings.image() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        started = true;
        compose("up", "-d").assertOk();
    }

    /** Stops the installation and deletes its volumes. Does nothing when it was never started. */
    public void downWithVolumes() {
        if (started) {
            compose("down", "-v", "--remove-orphans");
            started = false;
        }
    }

    public Docker.Result compose(String... args) {
        List<String> all = new ArrayList<>(List.of("compose", "-p", project, "--project-directory", dir.toString(),
                "--env-file", dir.resolve(".env").toString(),
                "-f", Settings.releaseCompose().toString(), "-f", dir.resolve("image-override.yml").toString()));
        all.addAll(List.of(args));
        return Docker.run(dir, all);
    }

    /** The one container of a service. */
    public String containerId(String service) {
        String id = compose("ps", "-q", service).assertOk().output().strip();
        assertThat(id).as("containers of service `%s`", service).isNotEmpty().doesNotContain("\n");
        return id;
    }

    public JsonNode state(String containerId) {
        return Json.parse(Docker.run("inspect", "--format", "{{json .State}}", containerId).assertOk().output().strip());
    }

    /** The Docker health status, or null when the container has no health check. */
    public String health(String containerId) {
        JsonNode health = state(containerId).get("Health");
        return health == null || health.isNull() ? null : health.get("Status").asString();
    }

    public int restartCount(String containerId) {
        return Integer.parseInt(Docker.run("inspect", "--format", "{{.RestartCount}}", containerId).assertOk().output().strip());
    }

    /** Waits until the container is healthy; fails with its last status and log lines otherwise. */
    public void awaitHealthy(String containerId, Duration atMost) {
        AtomicReference<String> last = new AtomicReference<>();
        try {
            Awaitility.await("app healthy").atMost(atMost).pollInterval(Duration.ofSeconds(2)).pollInSameThread()
                    .until(() -> {
                        String status = health(containerId);
                        last.set(status);
                        assertThat(status).as("the app service has no health check in the Compose file").isNotNull();
                        return "healthy".equals(status);
                    });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("app did not become healthy within " + atMost + " (last status: " + last.get()
                    + "). Last log lines:\n" + Docker.tail(logs("app"), 40), e);
        }
    }

    public String logs(String service) {
        return compose("logs", "--no-color", "--no-log-prefix", service).output();
    }

    /** The host port that {@code service:port} is published on. */
    public int publishedPort(String service, int port) {
        String out = compose("port", service, String.valueOf(port)).assertOk().output().strip();
        String first = out.lines().findFirst().orElse("");
        assertThat(first).as("`%s` port %d is not published", service, port).matches(".*:\\d+");
        return Integer.parseInt(first.substring(first.lastIndexOf(':') + 1));
    }

    /** The Docker network the container is on (the Compose default network). */
    public String network(String containerId) {
        JsonNode nets = Json.parse(Docker.run("inspect", "--format", "{{json .NetworkSettings.Networks}}", containerId)
                .assertOk().output().strip());
        List<String> names = new ArrayList<>(nets.propertyNames());
        assertThat(names).as("networks of the app container").isNotEmpty();
        return names.getFirst();
    }

    public static Map<String, String> settings(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public void saveLog(String name, String text) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
