package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Slice 2 on installations that were there before it (ADR D-24): the samples kept from 0.1.0 and
 * 0.2.0 in {@code deploy/upgrade/samples/} are restored and the image under test is started on
 * them, and the sample generator of this version fills what the slice adds.
 *
 * <p>Each test starts one stack of its own and removes it before it ends, so only one installation
 * runs at a time. The accounts and passwords are the ones {@code deploy/upgrade/popdesk_stack.py}
 * gives every sample.
 */
@Tag("deploy")
class ManagerUpgradeTest {

    private static final String SAMPLE_ADMIN = "admin@sample.popdesk.test";
    private static final String SAMPLE_ADMIN_PASSWORD = "sample-Admin-Password-1";
    private static final String SAMPLE_PASSWORD = "sample-People-Password-1";
    private static final String SAMPLE_CUSTOMER = "customer@sample.popdesk.test";
    private static final String SAMPLE_SUSPENDED = "suspended@sample.popdesk.test";

    private static final Duration SLOW = Duration.ofMinutes(15);
    private static final List<String> PROJECTS = new ArrayList<>();

    @AfterAll
    static void cleanUp() {
        for (String project : PROJECTS) {
            Cli.run(Stack.UP, Cli.docker("compose", "-p", project, "down", "-v", "--remove-orphans"));
        }
    }

    /** A stack with the dump restored and the image under test started on it. Close it to remove it. */
    private static Stack restored(String purpose, Path dump) {
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

    private static List<JsonNode> items(ApiResponse page) {
        List<JsonNode> out = new ArrayList<>();
        page.json().path("items").forEach(out::add);
        return out;
    }

    /** The customers of every company, by company code (a sample has fewer than 100 of each). */
    private static Map<String, List<JsonNode>> customersByCompany(Person admin) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        for (JsonNode company : items(admin.client().get("/api/console/companies").query("size", 100).expect(200))) {
            String code = company.path("code").asString(null);
            out.put(code, items(admin.client().get("/api/console/companies/" + code + "/customers")
                    .query("size", 100).expect(200)));
        }
        return out;
    }

    private static boolean isManager(JsonNode customer) {
        return customer.path("manager").isBoolean() && customer.path("manager").booleanValue();
    }

    private static boolean isNoManager(JsonNode customer) {
        return customer.path("manager").isBoolean() && !customer.path("manager").booleanValue();
    }

    // S2-AC-51

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0"})
    @DisplayName("S2-AC-51 on a sample of an older version this image finishes migrating and starts; no customer "
            + "that was there is a manager, every membership has a row ID of its own once a manager lists the "
            + "members, and the account suspended in the 0.2.0 sample still cannot sign in")
    void olderSampleGetsRowsWithoutManagers(String version) {
        Path dump = Repo.require("deploy/upgrade/samples/" + version + "/sample.dump",
                "the sample kept from " + version + ", deploy/upgrade/README.md");

        try (Stack stack = restored("managers" + version.replace(".", ""), dump)) {
            Db db = stack.db();
            assertThat(db.count("select count(*) from " + Db.quote(db.schema())
                    + ".flyway_schema_history where not success")).as("failed migrations").isZero();
            Person admin = new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
            if (version.equals("0.2.0")) {
                List<JsonNode> suspended = items(admin.client().get("/api/console/accounts").query("size", 100)
                        .expect(200)).stream()
                        .filter(a -> SAMPLE_SUSPENDED.equals(a.path("email").asString(null))).toList();
                assertThat(suspended).as("the account %s of the sample", SAMPLE_SUSPENDED).singleElement()
                        .satisfies(a -> assertThat(a.path("status").asString(null)).isEqualTo("SUSPENDED"));
                ApiResponse signIn = stack.console().post("/api/public/sign-in")
                        .json(Map.of("email", SAMPLE_SUSPENDED, "password", SAMPLE_PASSWORD)).send();
                assertThat(signIn.status()).as("sign-in of the suspended account: %s", signIn.snippet())
                        .isEqualTo(401);
            }

            Map<String, List<JsonNode>> customers = customersByCompany(admin);
            List<JsonNode> all = customers.values().stream().flatMap(List::stream).toList();
            assertThat(all).as("customers of the sample's companies").isNotEmpty();
            assertThat(all).as("every customer that was there has manager false").allMatch(
                    ManagerUpgradeTest::isNoManager);

            String code = customers.entrySet().stream()
                    .filter(e -> e.getValue().stream()
                            .anyMatch(c -> SAMPLE_CUSTOMER.equals(c.path("email").asString(null))))
                    .map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new AssertionError("No company with " + SAMPLE_CUSTOMER + " in " + customers));
            long accountId = customers.get(code).stream()
                    .filter(c -> SAMPLE_CUSTOMER.equals(c.path("email").asString(null)))
                    .findFirst().orElseThrow().path("accountId").asLong(-1);
            admin.client().put(ManagerSteps.managerOf(code, accountId)).expect(200);
            ApiClient portal = stack.portal();
            portal.post("/api/public/sign-in").json(Map.of("email", SAMPLE_CUSTOMER, "password", SAMPLE_PASSWORD))
                    .expect(204);

            List<JsonNode> rows = items(portal.get(ManagerSteps.members(code)).query("size", 100).expect(200));

            assertThat(rows).extracting(r -> r.path("email").asString(null)).as("rows of the member list of %s", code)
                    .containsExactlyInAnyOrderElementsOf(customers.get(code).stream()
                            .map(c -> c.path("email").asString(null)).toList());
            List<String> ids = rows.stream().map(r -> r.path("id").asString("")).toList();
            assertThat(ids).as("row IDs").allMatch(id -> ManagerSteps.ROW_ID.matcher(id).matches())
                    .doesNotHaveDuplicates();
        }
    }

    // S2-AC-52

    @Test
    @DisplayName("S2-AC-52 the installation the sample generator fills with this image has a customer marked as "
            + "a manager and a pending membership request")
    void sampleHasAManagerAndAPendingRequest() {
        Path out = tempDir().resolve("sample");
        String project = "pdt-managers-sample-" + UUID.randomUUID().toString().substring(0, 8);
        PROJECTS.add(project);
        Cli.run(SLOW, List.of(System.getenv().getOrDefault("POPDESK_PYTHON", "python"),
                        Repo.makeSample().toString(), "--image", TestImage.require(), "--out", out.toString(),
                        "--project", project),
                Map.of("POPDESK_TEST_RESOURCES", Repo.forCompose(Repo.resourceDir())), null, Repo.root())
                .expectOk("make-sample.py");
        assertThat(out.resolve("sample.dump")).as("sample.dump").isRegularFile();

        try (Stack stack = restored("managerssample", out.resolve("sample.dump"))) {
            Person admin = new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);

            List<JsonNode> managers = customersByCompany(admin).values().stream().flatMap(List::stream)
                    .filter(ManagerUpgradeTest::isManager).toList();
            assertThat(managers).as("customers marked as a manager in the sample").isNotEmpty();
            ApiResponse requests = admin.client().get("/api/console/membership-requests").query("size", 100)
                    .expect(200);
            assertThat(items(requests)).as("pending membership requests in the sample").isNotEmpty();
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("popdesk-managers-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
