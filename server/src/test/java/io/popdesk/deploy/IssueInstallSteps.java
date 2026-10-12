package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

/**
 * Steps the deploy tests of slice 3 share ({@link IssueUpgradeTest}, {@link RestoreForeignExtensionTest},
 * {@link IssueSampleTest}): an installation started on a kept sample, a backup and a restore as
 * another database role, and the sample this version's generator makes, restored into a database of
 * its own. The tests of earlier slices keep their own copies of these steps.
 */
final class IssueInstallSteps {

    /** The accounts {@code deploy/upgrade/popdesk_stack.py} gives every sample. */
    static final String SAMPLE_ADMIN = "admin@sample.popdesk.test";
    static final String SAMPLE_ADMIN_PASSWORD = "sample-Admin-Password-1";
    static final String SAMPLE_PASSWORD = "sample-People-Password-1";
    static final String SAMPLE_CUSTOMER = "customer@sample.popdesk.test";

    private static final String DUMP_IN_APP = "/tmp/popdesk-test.dump";
    private static final Duration SLOW = Duration.ofMinutes(15);

    /** A check on a database a dump was restored into. */
    interface DbCheck {
        void check(Db db);
    }

    private IssueInstallSteps() {}

    /** A file of the sample kept from {@code version} in {@code deploy/upgrade/samples/}. */
    static Path keptSample(String version, String file) {
        return Repo.require("deploy/upgrade/samples/" + version + "/" + file,
                "the sample kept from " + version + ", deploy/upgrade/README.md");
    }

    /** A stack with the dump restored and the image under test started on it. Close it to remove it. */
    static Stack startedOn(String purpose, Path dump) {
        Stack stack = Stack.release(purpose);
        try {
            stack.appEnv("POPDESK_ADMIN_EMAIL", SAMPLE_ADMIN).appEnv("POPDESK_ADMIN_PASSWORD", SAMPLE_ADMIN_PASSWORD);
            stack.up("db");
            stack.db().awaitReady();
            stack.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                            Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore",
                            "/tmp/restore.dump")
                    .expectOk("popdesk restore of " + dump);
            stack.up("app");
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    static Person sampleAdmin(Stack stack) {
        return new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
    }

    static List<JsonNode> items(ApiResponse page) {
        List<JsonNode> out = new ArrayList<>();
        page.json().path("items").forEach(out::add);
        return out;
    }

    static List<String> keys(ApiResponse page) {
        return items(page).stream().map(item -> item.path("key").asString("")).toList();
    }

    /** Copies a {@code popdesk backup} dump of the running installation to the host. */
    static Path backup(Stack stack) {
        stack.exec("app", null, "popdesk", "backup", DUMP_IN_APP).expectOk("popdesk backup");
        Path file = stack.work().resolve("backup.dump");
        stack.command("cp", "app:" + DUMP_IN_APP, file.toString()).expectOk("copy the dump out of app");
        file.toFile().setReadable(true, false);
        return file;
    }

    /** {@code popdesk restore} in a new container of the app service, as this database role. */
    static Cli.Result restoreAs(Stack stack, Path dump, String user, String password) {
        return stack.command(Duration.ofMinutes(5), "run", "--rm", "-T",
                "-e", "POPDESK_DB_USER=" + user,
                "-e", "POPDESK_DB_PASSWORD=" + password,
                "-v", dump.toAbsolutePath() + ":/tmp/restore.dump:ro", "app", "popdesk", "restore", "/tmp/restore.dump");
    }

    /** Removes db's volume and starts db again: a new, empty database. */
    static void emptyDatabase(Stack stack, Db db) {
        String volume = dbVolume(stack);
        stack.command("rm", "-f", "-s", "db").expectOk("remove db");
        Cli.run(Duration.ofMinutes(1), Cli.docker("volume", "rm", volume)).expectOk("remove the volume " + volume);
        stack.up("db");
        db.awaitReady();
        assertThat(db.userTableCount()).as("tables in the new database").isZero();
    }

    /** The real name of the volume that holds db's files (the test override renamed it). */
    private static String dbVolume(Stack stack) {
        ComposeModel compose = ComposeModel.resolved(stack.config());
        return compose.mounts("db").stream()
                .filter(m -> m.target().equals("/var/lib/postgresql/data"))
                .map(m -> stack.project() + "-" + m.source().toLowerCase())
                .findFirst()
                .orElseThrow(() -> new AssertionError("db has no volume at /var/lib/postgresql/data"));
    }

    /**
     * Runs {@code deploy/upgrade/make-sample.py} with the image under test as the compose project
     * {@code project} and gives the directory with {@code sample.dump}. Remove the project afterwards
     * ({@link #removeProject}).
     */
    static Path makeSample(String project) {
        Path out;
        try {
            out = Files.createTempDirectory("popdesk-issue-sample-").resolve("sample");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String python = System.getenv().getOrDefault("POPDESK_PYTHON", "python");
        List<String> command = List.of(python, Repo.makeSample().toString(), "--image", TestImage.require(), "--out",
                out.toString(), "--project", project);
        Cli.run(SLOW, command, Map.of("POPDESK_TEST_RESOURCES", Repo.forCompose(Repo.resourceDir())), null, Repo.root())
                .expectOk("make-sample.py");
        return out;
    }

    static void removeProject(String project) {
        Cli.run(Stack.UP, Cli.docker("compose", "-p", project, "down", "-v", "--remove-orphans"));
    }

    /** Restores a dump into a new PostgreSQL container of the release file's db image and user, and runs {@code check}. */
    static void withRestored(Path dump, DbCheck check) {
        ComposeModel compose;
        try (Stack stack = Stack.of("issue-sample", Repo.releaseCompose())) {
            compose = ComposeModel.resolved(stack.config());
        }
        Map<String, Object> env = compose.environment("db");
        String user = String.valueOf(env.getOrDefault("POSTGRES_USER", "postgres"));
        String database = String.valueOf(env.getOrDefault("POSTGRES_DB", user));
        DockerImageName image = DockerImageName.parse(compose.image("db")).asCompatibleSubstituteFor("postgres");
        try (PostgreSQLContainer pg = new PostgreSQLContainer(image)
                .withUsername(user).withPassword(Stack.DB_PASSWORD).withDatabaseName(database)) {
            try {
                pg.start();
            } catch (RuntimeException e) {
                throw new AssertionError("Could not start " + image + ": " + e, e);
            }
            pg.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/sample.dump");
            ExecResult restore = pg.execInContainer("pg_restore", "--no-owner", "-U", user, "-d", database,
                    "/tmp/sample.dump");
            Db db = new Db(pg.getHost(), () -> pg.getMappedPort(5432), database, user, Stack.DB_PASSWORD);
            assertThat(db.userTableCount()).as("tables after pg_restore of %s: %s", dump, restore.getStderr())
                    .isPositive();
            check.check(db);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }
}
