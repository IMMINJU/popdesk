package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The database settings of the release Compose file, {@code deploy/compose.yml} (verify.md V-12):
 * {@code POPDESK_DB_PASSWORD} has no default (configuration.md "Secrets"), so Compose itself must
 * refuse to go on without it, and the {@code db} health check must ask over TCP, which the temporary
 * server of the first initialization does not serve. Nothing is started.
 */
@Tag("deploy")
class ReleaseComposeDatabaseTest {

    private static final String KEY = "POPDESK_DB_PASSWORD";

    @ParameterizedTest(name = "V-12 [{0}] docker compose config fails and names POPDESK_DB_PASSWORD")
    @ValueSource(strings = {"unset", "empty"})
    @DisplayName("V-12 without POPDESK_DB_PASSWORD docker compose config fails and names the key")
    void configFailsWithoutDbPassword(String how, @TempDir Path dir) {
        Composed config = configWithoutDbPassword(how, dir);

        assertThat(config.exit()).as("exit code of docker compose config with %s %s:%n%s", KEY, how, config.output())
                .isNotZero();
        assertThat(config.output()).as("what docker compose config printed").contains(KEY);
    }

    @ParameterizedTest(name = "V-12 [{0}] the db health check asks over TCP")
    @ValueSource(strings = {"file", "config"})
    @DisplayName("V-12 the db health check runs pg_isready against 127.0.0.1, not the socket")
    void dbHealthCheckUsesTcp(String how) {
        ComposeModel compose;
        if (how.equals("file")) {
            compose = ComposeModel.read(Repo.releaseCompose());
        } else {
            try (Stack stack = Stack.of("compose", Repo.releaseCompose())) {
                compose = ComposeModel.resolved(stack.config());
            }
        }

        Object test = ComposeModel.map(compose.service("db").get("healthcheck")).get("test");
        String command = test instanceof List<?> words
                ? words.stream().map(String::valueOf).collect(Collectors.joining(" "))
                : String.valueOf(test);

        assertThat(command).as("db health check in %s", compose.origin()).contains("pg_isready");
        assertThat(command).as("db health check in %s names the host 127.0.0.1", compose.origin())
                .containsPattern("(-h\\s*|--host[= ])127\\.0\\.0\\.1(\\s|$)");
    }

    /** How {@code docker compose config} ended, and its standard output and error together. */
    private record Composed(int exit, String output) {}

    /**
     * Runs {@code docker compose config} on a copy of the release file in an empty folder, so that an
     * {@code .env} file next to the repository's file cannot give the value. {@code Cli} can add to
     * the environment but not take a variable out of it, so this starts the process itself.
     */
    private static Composed configWithoutDbPassword(String how, Path dir) {
        try {
            Path compose = Files.copy(Repo.releaseCompose(), dir.resolve("compose.yml"));
            ProcessBuilder builder = new ProcessBuilder("docker", "compose", "-f", compose.toString(), "config")
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            if (how.equals("empty")) {
                builder.environment().put(KEY, "");
            } else {
                builder.environment().remove(KEY);
            }
            Process process = builder.start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("docker compose config did not end within 2 minutes:\n" + output);
            }
            return new Composed(process.exitValue(), output);
        } catch (IOException e) {
            throw new AssertionError("Could not run docker compose config: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running docker compose config", e);
        }
    }
}
