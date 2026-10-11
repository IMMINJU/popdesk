package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upgrading an installation of 0.1.0 to the image under test (ADR D-24). The sample kept from
 * 0.1.0 is in {@code deploy/upgrade/samples/0.1.0/} ({@code sample.dump},
 * {@code sample.expected.json}, {@code sample.image}); {@code deploy/upgrade/make-sample.py} of that
 * version made it, with the accounts and passwords named below.
 *
 * <p>The first test runs the upgrade harness on the sample. The others restore the same dump into a
 * stack of their own, start the image on it, and use the accounts that were there before the
 * upgrade.
 */
@Tag("deploy")
class UpgradeFrom010Test {

    private static final String SAMPLE = "deploy/upgrade/samples/0.1.0/";

    /** The first admin and the invited people of the 0.1.0 sample. */
    private static final String SAMPLE_ADMIN = "admin@sample.popdesk.test";
    private static final String SAMPLE_ADMIN_PASSWORD = "sample-Admin-Password-1";
    private static final String SAMPLE_PASSWORD = "sample-People-Password-1";
    private static final String SAMPLE_PENDING = "pending@sample.popdesk.test";

    private static final Duration SLOW = Duration.ofMinutes(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> CHECKS = List.of("issues", "attachments", "outbox", "audit");
    private static final List<String> PROJECTS = new ArrayList<>();

    /** The stack restored from the sample, with how many migrations its history had before the app started. */
    private record Upgraded(Stack stack, long migrationsBefore) {}

    private static final Once<Upgraded> UPGRADED = new Once<>("restoring the 0.1.0 sample and starting the image",
            UpgradeFrom010Test::upgrade);

    @AfterAll
    static void cleanUp() {
        Upgraded upgraded = UPGRADED.peek();
        if (upgraded != null) {
            upgraded.stack().close();
        }
        for (String project : PROJECTS) {
            Cli.run(Stack.UP, Cli.docker("compose", "-p", project, "down", "-v", "--remove-orphans"));
        }
    }

    private static Path sample(String file) {
        return Repo.require(SAMPLE + file, "the sample kept from 0.1.0, deploy/upgrade/README.md");
    }

    private static Upgraded upgrade() {
        Path dump = sample("sample.dump");
        Stack stack = Stack.release("upgrade010");
        try {
            stack.appEnv("POPDESK_ADMIN_EMAIL", SAMPLE_ADMIN).appEnv("POPDESK_ADMIN_PASSWORD", SAMPLE_ADMIN_PASSWORD);
            stack.up("db");
            Db db = stack.db();
            db.awaitReady();
            stack.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                            Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore",
                            "/tmp/restore.dump")
                    .expectOk("popdesk restore of the 0.1.0 sample");
            long before = migrations(db);
            stack.up("app");
            stack.awaitHealthy("app");
            return new Upgraded(stack, before);
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static long migrations(Db db) {
        return db.count("select count(*) from " + Db.quote(db.schema()) + ".flyway_schema_history where success");
    }

    @Test
    @DisplayName("S1A-AC-80 the upgrade harness restores the 0.1.0 sample, starts this image on it, and every "
            + "sample check passes")
    void harnessPassesOnThe010Sample() {
        sample("sample.dump");
        sample("sample.expected.json");
        Path work = tempDir();
        String project = "pdt-upgrade010-" + UUID.randomUUID().toString().substring(0, 8);
        PROJECTS.add(project);
        List<String> command = List.of(System.getenv().getOrDefault("POPDESK_PYTHON", "python"),
                Repo.upgradeHarness().toString(), "--sample", Repo.path(SAMPLE).toString(), "--image",
                TestImage.require(), "--work", work.toString(), "--project", project);

        Cli.Result harness = Cli.run(SLOW, command,
                Map.of("POPDESK_TEST_RESOURCES", Repo.forCompose(Repo.resourceDir())), null, Repo.root());

        assertThat(harness.exit()).as("upgrade-test.py on the 0.1.0 sample\n%s", harness).isZero();
        Path file = work.resolve("result.json");
        assertThat(file).as("result.json of the harness").isRegularFile();
        JsonNode result = JSON.readTree(read(file));
        assertThat(result.path("started").asBoolean(false)).as("started in %s", result).isTrue();
        for (String check : CHECKS) {
            assertThat(result.path("checks").path(check).asBoolean(false)).as("check %s in %s", check, result).isTrue();
        }
    }

    @Test
    @DisplayName("S1A-AC-80 starting this image on the restored 0.1.0 sample runs migrations: the history has more "
            + "successful rows than the dump, none failed, and the sample's admin signs in")
    void migrationsRunOnThe010Sample() {
        Upgraded upgraded = UPGRADED.get();
        Db db = upgraded.stack().db();

        assertThat(upgraded.migrationsBefore()).as("migrations in the 0.1.0 dump").isPositive();
        assertThat(migrations(db)).as("successful migrations after the app started")
                .isGreaterThan(upgraded.migrationsBefore());
        assertThat(db.count("select count(*) from " + Db.quote(db.schema())
                + ".flyway_schema_history where not success")).as("failed migrations").isZero();
        assertThat(new AppSteps(upgraded.stack()).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD).accountId())
                .as("account ID of the sample's admin").isPositive();
    }

    @ParameterizedTest
    @CsvSource({"agent@sample.popdesk.test, console", "customer@sample.popdesk.test, portal"})
    @DisplayName("S1A-AC-90 an account that was ACTIVE in 0.1.0 counts as accepted after the upgrade: an admin "
            + "suspends it and reactivates it, and it signs in again with its password")
    void accountFrom010IsSuspendedAndReactivated(String email, String app) {
        Stack stack = UPGRADED.get().stack();
        Person admin = new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
        JsonNode account = account(admin, email);
        assertThat(account.path("status").asString(null)).as("status of %s after the upgrade", email)
                .isEqualTo("ACTIVE");
        String path = "/api/console/accounts/" + account.path("id").asLong();

        ApiResponse suspended = admin.client().post(path + "/suspend").send();

        assertThat(suspended.status()).as("%s: %s", suspended, suspended.snippet()).isEqualTo(200);
        assertThat(suspended.jsonText("status")).isEqualTo("SUSPENDED");
        assertThat(signIn(stack, app, email).status()).as("sign-in of the suspended %s", email).isEqualTo(401);

        ApiResponse reactivated = admin.client().post(path + "/reactivate").send();

        assertThat(reactivated.status()).as("%s: %s", reactivated, reactivated.snippet()).isEqualTo(200);
        assertThat(reactivated.jsonText("status")).isEqualTo("ACTIVE");
        assertThat(account(admin, email).path("status").asString(null)).isEqualTo("ACTIVE");
        assertThat(signIn(stack, app, email).status()).as("sign-in of the reactivated %s", email).isEqualTo(204);
    }

    @Test
    @DisplayName("S1A-AC-90 an account that was INVITED in 0.1.0 counts as not accepted after the upgrade: "
            + "reactivating it is 400 INVITATION_NOT_ACCEPTED, suspending it 400 ACCOUNT_NOT_ACTIVE, and it "
            + "stays INVITED")
    void invitedAccountFrom010IsNotAccepted() {
        Stack stack = UPGRADED.get().stack();
        Person admin = new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
        JsonNode account = account(admin, SAMPLE_PENDING);
        assertThat(account.path("status").asString(null)).as("status of %s after the upgrade", SAMPLE_PENDING)
                .isEqualTo("INVITED");
        String path = "/api/console/accounts/" + account.path("id").asLong();

        ApiResponse reactivated = admin.client().post(path + "/reactivate").send();
        ApiResponse suspended = admin.client().post(path + "/suspend").send();

        assertThat(reactivated.status()).as("%s: %s", reactivated, reactivated.snippet()).isEqualTo(400);
        assertThat(reactivated.errorCode()).isEqualTo("INVITATION_NOT_ACCEPTED");
        assertThat(suspended.status()).as("%s: %s", suspended, suspended.snippet()).isEqualTo(400);
        assertThat(suspended.errorCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(account(admin, SAMPLE_PENDING).path("status").asString(null)).isEqualTo("INVITED");
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("popdesk-upgrade010-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file + ": " + e, e);
        }
    }

    /** The account with this email from {@code GET /api/console/accounts} (the sample has fewer than 100). */
    private static JsonNode account(Person admin, String email) {
        ApiResponse page = admin.client().get("/api/console/accounts").query("size", 100).expect(200);
        for (JsonNode item : page.json().path("items").values()) {
            if (email.equals(item.path("email").asString(null))) {
                return item;
            }
        }
        throw new AssertionError("No account " + email + " in " + page.snippet());
    }

    private static ApiResponse signIn(Stack stack, String app, String email) {
        ApiClient client = app.equals("portal") ? stack.portal() : stack.console();
        return client.post("/api/public/sign-in").json(Map.of("email", email, "password", SAMPLE_PASSWORD)).send();
    }
}
