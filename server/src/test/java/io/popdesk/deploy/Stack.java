package io.popdesk.deploy;

import io.popdesk.support.ApiClient;
import io.popdesk.support.Wait;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Docker Compose project of popdesk, started from the repository's Compose files the way an
 * operator would, with a generated override file that only makes it safe to run next to other tests:
 *
 * <ul>
 *   <li>the {@code app} image is the image under test ({@link TestImage});
 *   <li>published ports get random host ports, and {@code app} 8081 and {@code db} 5432 are published
 *       too, so the test can read metrics and look into the database;
 *   <li>volume names get the project name in front, so two projects (or a real installation on the
 *       same machine) never share {@code popdesk-db};
 *   <li>{@code app} gets the first admin and any keys the test adds ({@link #appEnv}).
 * </ul>
 *
 * <p>Other settings come from the Compose files and from environment variables, as an operator's
 * {@code .env} would give them: {@code POPDESK_DB_PASSWORD}, {@code POPDESK_ADMIN_EMAIL},
 * {@code POPDESK_ADMIN_PASSWORD}. Test resource files can be mounted with
 * {@code ${POPDESK_TEST_RESOURCES}} (this package's resource folder) in an extra Compose file.
 *
 * <p>Each project has its own name. {@link #close} removes it with {@code down -v}.
 */
final class Stack implements AutoCloseable {

    static final String ADMIN_EMAIL = "admin@popdesk.test";
    static final String ADMIN_PASSWORD = "admin-Password-1";
    static final String DB_PASSWORD = "db-Password-1";
    static final String PORTAL_HOST = "portal.localhost";
    static final String CONSOLE_HOST = "console.localhost";

    static final Duration UP = Duration.ofMinutes(10);
    static final Duration HEALTHY = Duration.ofMinutes(4);
    private static final Duration COMMAND = Duration.ofMinutes(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String project;
    private final List<Path> files = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final Map<String, String> appEnv = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> published = new LinkedHashMap<>();
    private final Path work;
    private boolean started;

    private Stack(String purpose) {
        this.project = "pdt-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            this.work = Files.createTempDirectory("popdesk-" + purpose + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        env.put("POPDESK_DB_PASSWORD", DB_PASSWORD);
        env.put("POPDESK_ADMIN_EMAIL", ADMIN_EMAIL);
        env.put("POPDESK_ADMIN_PASSWORD", ADMIN_PASSWORD);
        env.put("POPDESK_TEST_IMAGE", TestImage.name());
        env.put("POPDESK_TEST_RESOURCES", Repo.forCompose(Repo.resourceDir()));
        appEnv.put("POPDESK_ADMIN_EMAIL", ADMIN_EMAIL);
        appEnv.put("POPDESK_ADMIN_PASSWORD", ADMIN_PASSWORD);
    }

    /**
     * The release Compose file with the image under test. Fails (an assertion) when the file or the
     * image is missing, before anything starts.
     */
    static Stack release(String purpose) {
        Path compose = Repo.releaseCompose();
        TestImage.require();
        Stack stack = new Stack(purpose);
        stack.files.add(compose);
        return stack;
    }

    /** Given Compose files only, for reading their configuration; needs no image. */
    static Stack of(String purpose, Path... composeFiles) {
        Stack stack = new Stack(purpose);
        stack.files.addAll(List.of(composeFiles));
        return stack;
    }

    /** Adds a Compose file after the ones already given (a later file overrides earlier ones). */
    Stack with(Path file) {
        files.add(file);
        return this;
    }

    /** Adds a Compose file from this package's test resources. */
    Stack withResource(String name) {
        return with(Repo.resource(name));
    }

    /** An environment variable for Compose itself, used in {@code ${...}} in the Compose files. */
    Stack env(String name, String value) {
        env.put(name, value);
        return this;
    }

    /** A key in the {@code app} container's environment. Takes effect at the next {@link #up}. */
    Stack appEnv(String name, String value) {
        appEnv.put(name, value);
        return this;
    }

    /** Publishes a container port of a service on a random host port, for the test to reach it. */
    Stack publish(String service, int containerPort) {
        published.computeIfAbsent(service, k -> new LinkedHashSet<>()).add(containerPort);
        return this;
    }

    /** Removes a key that the stack would otherwise give {@code app}. */
    Stack withoutAppEnv(String name) {
        appEnv.remove(name);
        return this;
    }

    String project() {
        return project;
    }

    Path work() {
        return work;
    }

    Map<String, String> environment() {
        return Map.copyOf(env);
    }

    // Compose

    /** {@code docker compose config --format json} of the given files, without the test override. */
    JsonNode config() {
        Cli.Result result = Cli.run(COMMAND, compose(false, "config", "--format", "json"), env, null, Repo.root());
        result.expectOk("docker compose config of " + files);
        return JSON.readTree(result.out());
    }

    /** Starts (or recreates, when its settings changed) the given services, or all of them. */
    void up(String... services) {
        writeOverride();
        List<String> args = new ArrayList<>(List.of("up", "-d", "--remove-orphans"));
        args.addAll(List.of(services));
        started = true;
        command(UP, args.toArray(String[]::new)).expectOk("docker compose up " + String.join(" ", services));
    }

    /** Runs {@code docker compose <args>} with the files and the override. */
    Cli.Result command(Duration timeout, String... args) {
        return Cli.run(timeout, compose(true, args), env, null, Repo.root());
    }

    Cli.Result command(String... args) {
        return command(COMMAND, args);
    }

    /** {@code docker compose exec -T service command...}: no terminal, {@code stdin} as input. */
    Cli.Result exec(String service, byte[] stdin, String... command) {
        List<String> args = new ArrayList<>(List.of("exec", "-T", service));
        args.addAll(List.of(command));
        return Cli.run(COMMAND, compose(true, args.toArray(String[]::new)), env, stdin, Repo.root());
    }

    String logs(String service) {
        Cli.Result result = command("logs", "--no-color", "--no-log-prefix", service);
        return result.out() + result.err();
    }

    /** The container of a service (running or not). Fails when there is none. */
    String containerId(String service) {
        String id = command("ps", "-a", "-q", service).out().strip();
        if (id.isEmpty()) {
            throw new AssertionError("No container for service " + service + " in " + project + "\n"
                    + command("ps", "-a"));
        }
        return id.lines().findFirst().orElseThrow();
    }

    /** Containers of the project with their service and image, from {@code docker compose ps}. */
    List<JsonNode> containers() {
        String out = command("ps", "-a", "--format", "json").expectOk("docker compose ps").out().strip();
        List<JsonNode> list = new ArrayList<>();
        if (out.startsWith("[")) {
            JSON.readTree(out).forEach(list::add);
        } else {
            out.lines().filter(l -> !l.isBlank()).forEach(l -> list.add(JSON.readTree(l)));
        }
        return list;
    }

    /** {@code docker inspect} of a service's container. */
    JsonNode inspect(String service) {
        String id = containerId(service);
        Cli.Result result = Cli.run(COMMAND, Cli.docker("inspect", id)).expectOk("docker inspect " + service);
        return JSON.readTree(result.out()).path(0);
    }

    /** The host port that a service's container port is published on. */
    int port(String service, int containerPort) {
        Cli.Result result = command("port", service, Integer.toString(containerPort));
        String out = result.out().strip();
        if (!result.ok() || out.isEmpty()) {
            throw new AssertionError("Port " + containerPort + " of " + service + " is not published\n" + result);
        }
        String first = out.lines().findFirst().orElseThrow();
        return Integer.parseInt(first.substring(first.lastIndexOf(':') + 1));
    }

    /** Waits until the service's health check says healthy. Fails with its state and log otherwise. */
    void awaitHealthy(String service) {
        awaitHealthy(service, HEALTHY);
    }

    void awaitHealthy(String service, Duration atMost) {
        try {
            Wait.until(service + " healthy", atMost, () -> {
                JsonNode health = inspect(service).path("State").path("Health");
                return "healthy".equals(health.path("Status").asString("")) ? health : null;
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\nstate: " + inspect(service).path("State") + "\nlog:\n"
                    + tail(logs(service)), e);
        }
    }

    // Clients

    ApiClient portal() {
        return new ApiClient(PORTAL_HOST, port("app", 8080));
    }

    ApiClient console() {
        return new ApiClient(CONSOLE_HOST, port("app", 8080));
    }

    /** The database, through {@code db} 5432 on its published host port. */
    Db db() {
        JsonNode dbEnv = config().path("services").path("db").path("environment");
        String user = dbEnv.path("POSTGRES_USER").asString("postgres");
        String password = dbEnv.path("POSTGRES_PASSWORD").asString(DB_PASSWORD);
        String database = dbEnv.path("POSTGRES_DB").asString(user);
        return new Db(() -> port("db", 5432), database, user, password);
    }

    /** {@code /actuator/prometheus} from the management port, as text. */
    String prometheus() {
        return Http.get("http://127.0.0.1:" + port("app", 8081) + "/actuator/prometheus");
    }

    @Override
    public void close() {
        if (started) {
            command(UP, "down", "-v", "--remove-orphans", "--timeout", "20");
        }
        try (Stream<Path> paths = Files.walk(work)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A leftover temp folder does not change any result.
        }
    }

    // The override

    private List<String> compose(boolean withOverride, String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project));
        for (Path file : files) {
            command.add("-f");
            command.add(file.toString());
        }
        if (withOverride) {
            command.add("-f");
            command.add(work.resolve("test-override.yml").toString());
        }
        command.addAll(List.of(args));
        return command;
    }

    private void writeOverride() {
        JsonNode config = config();
        StringBuilder y = new StringBuilder("services:\n");
        for (Map.Entry<String, JsonNode> service : config.path("services").properties()) {
            String name = service.getKey();
            JsonNode s = service.getValue();
            Set<Integer> targets = new LinkedHashSet<>();
            s.path("ports").forEach(p -> targets.add(p.path("target").asInt()));
            if (name.equals("app")) {
                targets.add(8080);
                targets.add(8081);
            }
            if (name.equals("db")) {
                targets.add(5432);
            }
            targets.addAll(published.getOrDefault(name, Set.of()));
            boolean hasName = s.has("container_name");
            if (targets.isEmpty() && !hasName && !name.equals("app")) {
                continue;
            }
            y.append("  ").append(name).append(":\n");
            if (hasName) {
                y.append("    container_name: !reset null\n");
            }
            if (!targets.isEmpty()) {
                y.append("    ports: !override\n");
                targets.forEach(t -> y.append("      - \"").append(t).append("\"\n"));
            }
            if (name.equals("app")) {
                y.append("    image: ").append(quote(TestImage.name())).append('\n');
                y.append("    environment:\n");
                appEnv.forEach((k, v) -> y.append("      ").append(k).append(": ").append(quote(v)).append('\n'));
            }
        }
        JsonNode volumes = config.path("volumes");
        if (!volumes.isEmpty()) {
            y.append("volumes:\n");
            for (Map.Entry<String, JsonNode> volume : volumes.properties()) {
                y.append("  ").append(volume.getKey()).append(":\n");
                y.append("    name: ").append(quote(project + "-" + volume.getKey().toLowerCase(Locale.ROOT)))
                        .append('\n');
            }
        }
        try {
            Files.writeString(work.resolve("test-override.yml"), y.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "$$") + "\"";
    }

    static String tail(String text) {
        return text.length() <= 8000 ? text : "..." + text.substring(text.length() - 8000);
    }
}
