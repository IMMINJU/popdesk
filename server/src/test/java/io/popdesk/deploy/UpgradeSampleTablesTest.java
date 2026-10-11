package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The sample data generator ({@code deploy/upgrade/make-sample.py}) on the image under test: the
 * dump it writes has a row in every business table, so a table this slice adds is in the sample the
 * next version upgrades from. The check names no table; it is the one of
 * {@code UpgradeHarnessTest} for the first slice, run again for this slice's image.
 */
@Tag("deploy")
class UpgradeSampleTablesTest {

    private static final Duration SLOW = Duration.ofMinutes(15);

    @Test
    @DisplayName("S1B-AC-41 the sample generator fills every business table of this image, the tables this "
            + "slice adds included")
    void sampleFillsEveryTableOfThisImage() throws IOException, InterruptedException {
        Path script = Repo.makeSample();
        String image = TestImage.require();
        Path out = Files.createTempDirectory("popdesk-sample-tables-");
        String project = "pdt-sampletables-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Cli.run(SLOW, List.of(System.getenv().getOrDefault("POPDESK_PYTHON", "python"), script.toString(),
                            "--image", image, "--out", out.toString(), "--project", project),
                    Map.of("POPDESK_TEST_RESOURCES", Repo.forCompose(Repo.resourceDir())), null, Repo.root())
                    .expectOk("make-sample.py");
        } finally {
            Cli.run(Stack.UP, Cli.docker("compose", "-p", project, "down", "-v", "--remove-orphans"));
        }
        Path dump = out.resolve("sample.dump");
        assertThat(dump).as("sample.dump").isRegularFile();

        ComposeModel compose;
        try (Stack stack = Stack.of("sampletables", Repo.releaseCompose())) {
            compose = ComposeModel.resolved(stack.config());
        }
        Map<String, Object> env = compose.environment("db");
        String user = String.valueOf(env.getOrDefault("POSTGRES_USER", "postgres"));
        String database = String.valueOf(env.getOrDefault("POSTGRES_DB", user));
        DockerImageName dbImage = DockerImageName.parse(compose.image("db")).asCompatibleSubstituteFor("postgres");
        try (PostgreSQLContainer pg = new PostgreSQLContainer(dbImage)
                .withUsername(user).withPassword(Stack.DB_PASSWORD).withDatabaseName(database)) {
            try {
                pg.start();
            } catch (RuntimeException e) {
                throw new AssertionError("Could not start " + dbImage + ": " + e, e);
            }
            pg.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/sample.dump");
            ExecResult restore = pg.execInContainer("pg_restore", "--no-owner", "-U", user, "-d", database,
                    "/tmp/sample.dump");
            Db db = new Db(pg.getHost(), () -> pg.getMappedPort(5432), database, user, Stack.DB_PASSWORD);
            assertThat(db.userTableCount()).as("tables after pg_restore of %s: %s", dump, restore.getStderr())
                    .isPositive();

            List<String> tables = db.businessTables();
            String schema = db.schema();
            List<String> empty = tables.stream()
                    .filter(t -> db.count("select count(*) from " + Db.quote(schema) + "." + Db.quote(t)) == 0)
                    .toList();

            assertThat(tables).as("business tables in the sample").isNotEmpty();
            assertThat(empty).as("business tables with no row in the sample").isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
