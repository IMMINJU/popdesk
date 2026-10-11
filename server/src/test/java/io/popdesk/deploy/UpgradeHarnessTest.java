package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The upgrade test harness and the sample data generator in {@code deploy/upgrade/} (process.md B
 * and D, ADR D-24, first-slice premortem section 8). How they are called is in this package's
 * README; in short:
 *
 * <pre>
 * python deploy/upgrade/make-sample.py --image IMAGE --out DIR --project NAME
 *     writes DIR/sample.dump (popdesk backup), DIR/sample.image (the image ID it ran),
 *     DIR/sample.expected.json (what the sample check expects); exit 0
 * python deploy/upgrade/upgrade-test.py --sample DIR --image IMAGE --work DIR --project NAME
 *         [--compose-file FILE ...]
 *     restores DIR/sample.dump into a new database, starts IMAGE, runs the sample check;
 *     writes WORK/result.json and WORK/app.log;
 *     exit 0 passed, 1 a sample check failed, 2 the app did not start or did not finish migrating
 * </pre>
 *
 * <p>In the first slice the old and the new image are the same image, so no migration runs; the
 * self test with a broken V999 migration shows that the checks can fail.
 */
@Tag("deploy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpgradeHarnessTest {

    private static final Duration SLOW = Duration.ofMinutes(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path WORK = tempDir();
    private static final Once<Path> SAMPLE = new Once<>("make-sample.py", UpgradeHarnessTest::makeSample);
    private static final List<String> PROJECTS = new ArrayList<>();
    private static final List<String> CHECKS = List.of("issues", "attachments", "outbox", "audit");

    /** The send queue's tables: a name that holds "outbox" or "queue" (as V999 assumes). */
    private static final String QUEUE_TABLES = "and (table_name like '%outbox%' or table_name like '%queue%') ";

    @AfterAll
    static void cleanUp() {
        for (String project : PROJECTS) {
            Cli.run(Stack.UP, Cli.docker("compose", "-p", project, "down", "-v", "--remove-orphans"));
        }
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-95 the sample generator writes a dump with the ID of the image that made it next to it")
    void sampleRecordsItsImage() throws IOException {
        Path sample = SAMPLE.get();

        assertThat(sample.resolve("sample.dump")).as("sample.dump").isRegularFile();
        assertThat(Files.size(sample.resolve("sample.dump"))).as("size of sample.dump").isPositive();
        assertThat(sample.resolve("sample.expected.json")).as("sample.expected.json").isRegularFile();
        assertThat(Files.readString(sample.resolve("sample.image"), StandardCharsets.UTF_8).strip())
                .as("sample.image").contains(TestImage.id());
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-95 what the sample check expects is not empty: issue keys, attachment hashes, and no empty "
            + "list or object")
    void sampleExpectsSomething() throws IOException {
        Path sample = SAMPLE.get();
        JsonNode expected = JSON.readTree(Files.readString(sample.resolve("sample.expected.json"),
                StandardCharsets.UTF_8));
        List<JsonNode> nodes = new ArrayList<>();
        collect(expected, nodes);

        assertThat(expected.isObject() && !expected.isEmpty()).as("sample.expected.json %s", expected).isTrue();
        assertThat(nodes).as("empty lists or objects in %s", expected)
                .noneMatch(n -> n.isContainer() && n.isEmpty());
        assertThat(nodes).as("issue keys ({company code}-{number}) in %s", expected)
                .anyMatch(n -> n.isString() && n.stringValue().matches("[A-Z0-9]+-[0-9]+"));
        assertThat(nodes).as("sha256 hashes of attachment contents in %s", expected)
                .anyMatch(n -> n.isString() && n.stringValue().matches("(?i)(sha256:)?[0-9a-f]{64}"));
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        out.add(node);
        node.values().forEach(child -> collect(child, out));
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-95, S1B-AC-41 the sample fills every business table of this slice")
    void sampleFillsEveryTable() {
        Path sample = SAMPLE.get();

        withRestored(sample.resolve("sample.dump"), db -> {
            List<String> tables = db.businessTables();
            assertThat(tables).as("business tables in the sample").isNotEmpty();
            String schema = db.schema();
            List<String> empty = tables.stream()
                    .filter(t -> db.count("select count(*) from " + Db.quote(schema) + "." + Db.quote(t)) == 0)
                    .toList();
            assertThat(empty).as("business tables with no row in the sample").isEmpty();
        });
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-95 the sample has the boundary values: a 255-character file name, a purging attachment, "
            + "a failed queue row")
    void sampleHasBoundaryValues() {
        Path sample = SAMPLE.get();

        withRestored(sample.resolve("sample.dump"), db -> {
            String schema = db.schema();
            List<Map<String, Object>> attachmentColumns = db.query("select table_name, column_name from "
                    + "information_schema.columns where table_schema = ? and table_name like '%attachment%' "
                    + "and data_type in ('text', 'character varying', 'USER-DEFINED')", schema);
            assertThat(attachmentColumns).as("text columns of attachment tables").isNotEmpty();
            assertThat(anyColumn(db, schema, attachmentColumns, "char_length(%s::text) = 255"))
                    .as("an attachment text value of 255 characters (the file name)").isTrue();
            assertThat(anyColumn(db, schema, attachmentColumns, "lower(%s::text) = 'purging'"))
                    .as("an attachment in state purging").isTrue();

            List<Map<String, Object>> textColumns = db.query("select table_name, column_name from "
                    + "information_schema.columns where table_schema = ? " + QUEUE_TABLES
                    + "and data_type in ('text', 'character varying', 'USER-DEFINED')", schema);
            List<Map<String, Object>> failedTimes = db.query("select table_name, column_name from "
                    + "information_schema.columns where table_schema = ? " + QUEUE_TABLES
                    + "and column_name like '%failed%' and data_type like 'timestamp%'", schema);
            assertThat(anyColumn(db, schema, textColumns, "lower(%s::text) = 'failed'")
                    || anyColumn(db, schema, failedTimes, "%s is not null"))
                    .as("a send queue row in state failed").isTrue();
        });
    }

    @Test
    @Order(4)
    @DisplayName("S0-AC-95 the harness restores the sample, starts the same image, and passes every sample check")
    void harnessPassesOnTheSameImage() {
        Path sample = SAMPLE.get();
        Path work = WORK.resolve("same");

        Cli.Result harness = harness(sample, work, List.of());

        assertThat(harness.exit()).as("upgrade-test.py\n%s", harness).isZero();
        JsonNode result = result(work);
        assertThat(result.path("started").asBoolean(false)).as("started in result.json %s", result).isTrue();
        for (String check : CHECKS) {
            assertThat(result.path("checks").path(check).asBoolean(false)).as("check %s in %s", check, result).isTrue();
        }
    }

    @Test
    @Order(5)
    @DisplayName("S0-AC-96 with a dump whose flyway_schema_history has a newer version, the app does not start "
            + "and the harness says so")
    void newerSchemaDoesNotStart() throws IOException {
        Path sample = SAMPLE.get();
        Path newer = WORK.resolve("newer-sample");
        Files.createDirectories(newer);
        for (String f : List.of("sample.image", "sample.expected.json")) {
            Files.copy(sample.resolve(f), newer.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        withRestored(sample.resolve("sample.dump"), newer.resolve("sample.dump"), db -> {
            String history = Db.quote(db.schema()) + ".flyway_schema_history";
            db.execute("insert into " + history + " (installed_rank, version, description, type, script, checksum, "
                    + "installed_by, installed_on, execution_time, success) select coalesce(max(installed_rank), 0) + 1, "
                    + "'9999', 'from a newer popdesk', 'SQL', 'V9999__from_a_newer_popdesk.sql', 0, current_user, now(), "
                    + "0, true from " + history);
        });
        Path work = WORK.resolve("newer");

        Cli.Result harness = harness(newer, work, List.of());

        assertThat(harness.exit()).as("upgrade-test.py on a newer schema (2: the app did not start)\n%s", harness)
                .isEqualTo(2);
        assertThat(result(work).path("started").asBoolean(true)).as("started in result.json").isFalse();
        Path log = work.resolve("app.log");
        assertThat(log).as("app.log of the harness").isRegularFile();
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(text).as("app.log: the app itself refused the newer schema (tasks.md section 7)").contains("9999");
        assertThat(text.toLowerCase(Locale.ROOT)).as("app.log: the app names the backup").contains("backup");
    }

    @Test
    @Order(6)
    @DisplayName("S0-AC-104 with a broken V999 migration added, the app starts and each of the four sample checks "
            + "fails")
    void brokenMigrationFailsTheHarness() {
        Path sample = SAMPLE.get();
        Path work = WORK.resolve("v999");

        Cli.Result harness = harness(sample, work, List.of(Repo.resource("upgrade/v999.compose.yml")));

        assertThat(harness.exit()).as("upgrade-test.py with V999 (1: a sample check failed)\n%s", harness).isEqualTo(1);
        JsonNode result = result(work);
        assertThat(result.path("started").asBoolean(false)).as("started in result.json %s", result).isTrue();
        for (String check : CHECKS) {
            JsonNode value = result.path("checks").path(check);
            assertThat(value.isBoolean() && !value.asBoolean()).as("check %s is false in %s", check, result).isTrue();
        }
    }

    private static Path makeSample() {
        Path script = Repo.makeSample();
        Repo.upgradeHarness();
        String image = TestImage.require();
        Path out = WORK.resolve("sample");
        Cli.Result result = python(List.of(script.toString(), "--image", image, "--out", out.toString(),
                "--project", project("sample")));
        result.expectOk("make-sample.py");
        return out;
    }

    private static Cli.Result harness(Path sample, Path work, List<Path> composeFiles) {
        List<String> args = new ArrayList<>(List.of(Repo.upgradeHarness().toString(), "--sample", sample.toString(),
                "--image", TestImage.require(), "--work", work.toString(), "--project", project("harness")));
        for (Path file : composeFiles) {
            args.add("--compose-file");
            args.add(file.toString());
        }
        return python(args);
    }

    private static Cli.Result python(List<String> args) {
        String python = System.getenv().getOrDefault("POPDESK_PYTHON", "python");
        List<String> command = new ArrayList<>();
        command.add(python);
        command.addAll(args);
        return Cli.run(SLOW, command, Map.of("POPDESK_TEST_RESOURCES", Repo.forCompose(Repo.resourceDir())), null,
                Repo.root());
    }

    private static JsonNode result(Path work) {
        Path file = work.resolve("result.json");
        assertThat(file).as("result.json of the harness").isRegularFile();
        try {
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String project(String purpose) {
        String name = "pdt-upgrade-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
        PROJECTS.add(name);
        return name;
    }

    private static boolean anyColumn(Db db, String schema, List<Map<String, Object>> columns, String condition) {
        for (Map<String, Object> c : columns) {
            String column = Db.quote((String) c.get("column_name"));
            String sql = "select count(*) from " + Db.quote(schema) + "." + Db.quote((String) c.get("table_name"))
                    + " where " + condition.formatted(column);
            if (db.count(sql) > 0) {
                return true;
            }
        }
        return false;
    }

    interface DbCheck {
        void check(Db db) throws IOException;
    }

    private static void withRestored(Path dump, DbCheck check) {
        withRestored(dump, null, check);
    }

    /**
     * Restores a dump into a new PostgreSQL container of the release file's db image and user, runs
     * {@code check}, and (when {@code dumpAgain} is set) writes the database back out with pg_dump.
     */
    private static void withRestored(Path dump, Path dumpAgain, DbCheck check) {
        ComposeModel compose;
        try (Stack stack = Stack.of("upgrade", Repo.releaseCompose())) {
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
            if (dumpAgain != null) {
                ExecResult again = pg.execInContainer("pg_dump", "-Fc", "-U", user, "-d", database, "-f", "/tmp/again.dump");
                assertThat(again.getExitCode()).as("pg_dump: %s", again.getStderr()).isZero();
                pg.copyFileFromContainer("/tmp/again.dump", dumpAgain.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("popdesk-upgrade-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
