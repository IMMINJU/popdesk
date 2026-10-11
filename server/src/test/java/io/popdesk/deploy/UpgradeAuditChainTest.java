package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.deploy.Metrics.Sample;
import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The audit rows of an installation of 0.1.0 after the upgrade to the image under test: every row
 * that was there gets its place and hash in the chain (ADR D-24, design section 1).
 *
 * <p>One installation serves every test. The 0.1.0 sample ({@code deploy/upgrade/samples/0.1.0/}) is
 * restored with {@code popdesk restore}, which adds its own {@code DATABASE_RESTORED} row to the old
 * schema. Then {@value #BULK} audit rows go in with SQL, the only way to have that many: later IDs
 * with earlier times, two rows per microsecond, so the order by time and then ID is not the order of
 * the IDs. Then the image starts on it with {@code -Xmx384m} (as {@code AttachmentLoadTest} sets the
 * heap), the sample's admin signs in, and the log is exported once, without a filter.
 */
@Tag("deploy")
class UpgradeAuditChainTest {

    private static final String SAMPLE = "deploy/upgrade/samples/0.1.0/";

    /** The first admin of the 0.1.0 sample (as in {@code UpgradeFrom010Test}). */
    private static final String SAMPLE_ADMIN = "admin@sample.popdesk.test";
    private static final String SAMPLE_ADMIN_PASSWORD = "sample-Admin-Password-1";

    private static final int BULK = 100_000;
    private static final String BULK_EMAIL = "bulk@sample.popdesk.test";
    private static final long MAX_HEAP = 384L * 1024 * 1024;

    /** The upgraded installation: the audit row IDs before the app started, by time and then ID. */
    private record Upgraded(Stack stack, List<Long> rowsBefore, long migrationsBefore) {}

    /** The one export: the chain, and the count its own AUDIT_EXPORTED entry holds. */
    private record Exported(Person admin, AuditFile.Chain chain, long recordedCount) {}

    private static final Once<Upgraded> UPGRADED = new Once<>("restoring the 0.1.0 sample with " + BULK
            + " more audit rows and starting the image", UpgradeAuditChainTest::upgrade);

    private static final Once<Exported> EXPORTED = new Once<>("exporting the audit log after the upgrade",
            UpgradeAuditChainTest::exportAll);

    @AfterAll
    static void cleanUp() {
        Upgraded upgraded = UPGRADED.peek();
        if (upgraded != null) {
            upgraded.stack().close();
        }
    }

    private static Path sample(String file) {
        return Repo.require(SAMPLE + file, "the sample kept from 0.1.0, deploy/upgrade/README.md");
    }

    private static Upgraded upgrade() {
        Path dump = sample("sample.dump");
        Stack stack = Stack.release("upgradechain");
        try {
            stack.appEnv("POPDESK_ADMIN_EMAIL", SAMPLE_ADMIN).appEnv("POPDESK_ADMIN_PASSWORD", SAMPLE_ADMIN_PASSWORD)
                    .appEnv("JDK_JAVA_OPTIONS", "-Xmx384m");
            stack.up("db");
            Db db = stack.db();
            db.awaitReady();
            stack.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                            Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore",
                            "/tmp/restore.dump")
                    .expectOk("popdesk restore of the 0.1.0 sample");
            String audit = Db.quote(db.schema()) + ".audit_log";
            db.execute("insert into " + audit + " (tenant_id, action, actor, target, changes, occurred_at) "
                    + "select (select min(tenant_id) from " + audit + "), 'SIGN_IN_SUCCEEDED', "
                    + "jsonb_build_object('accountId', 1, 'name', '견본 \"관리자\" \\ 😀', 'email', '" + BULK_EMAIL + "'), "
                    + "jsonb_build_object('type', 'ACCOUNT', 'accountId', 1), "
                    + "jsonb_build_object('n', n, 'list', jsonb_build_array('a', 'b'), 'flag', true, 'none', null), "
                    + "timestamptz '2026-01-01T00:00:00Z' + ((" + BULK + " - n) / 2) * interval '1 microsecond' "
                    + "from generate_series(1, " + BULK + ") n");
            List<Long> rowsBefore = db.query("select id from " + audit + " order by occurred_at, id").stream()
                    .map(row -> ((Number) row.get("id")).longValue()).toList();
            long migrationsBefore = migrations(db);
            stack.up("app");
            stack.awaitHealthy("app");
            return new Upgraded(stack, rowsBefore, migrationsBefore);
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static Exported exportAll() {
        Upgraded upgraded = UPGRADED.get();
        Person admin = new AppSteps(upgraded.stack()).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
        AuditFile.Chain chain = AuditFile.export(admin,
                line -> !BULK_EMAIL.equals(line.path("actor").path("email").asString(null)));
        ApiResponse recorded = admin.client().get(AuditFile.LOG).query("action", "AUDIT_EXPORTED")
                .query("actorAccountId", admin.accountId()).expect(200);
        assertThat(recorded.json().path("items")).as("AUDIT_EXPORTED entries of the sample's admin in %s",
                recorded.snippet()).hasSize(1);
        return new Exported(admin, chain,
                AuditFile.number(recorded.json().path("items").path(0).path("changes"), "count"));
    }

    private static long migrations(Db db) {
        return db.count("select count(*) from " + Db.quote(db.schema()) + ".flyway_schema_history where success");
    }

    @Test
    @DisplayName("S1B-AC-12 after the upgrade every audit row that was there is in the chain from seq 1, in the "
            + "order of time and then ID, each with the hash a new entry would get")
    void rowsFromBeforeTheUpgradeAreChainedByTimeThenId() {
        List<Long> rowsBefore = UPGRADED.get().rowsBefore();
        AuditFile.Chain chain = EXPORTED.get().chain();

        assertThat(rowsBefore.size()).as("audit rows before the upgrade").isGreaterThan(BULK);
        assertThat(chain.firstSeq()).as("seq of the first line of the export").isEqualTo(1);
        assertThat(chain.firstPrevHash()).as("prevHash of the first line of the export").isEqualTo(AuditFile.ZERO_HASH);
        assertThat(chain.ids().size()).as("lines of the export").isGreaterThanOrEqualTo(rowsBefore.size());
        for (int i = 0; i < rowsBefore.size(); i++) {
            if (!rowsBefore.get(i).equals(chain.ids().get(i))) {
                throw new AssertionError("Line " + (i + 1) + " of the export is the row " + chain.ids().get(i)
                        + ", but by time and then ID the row " + rowsBefore.get(i) + " comes there");
            }
        }
    }

    @Test
    @DisplayName("S1B-AC-40 the 0.1.0 sample upgrades: the migrations end, every audit row of the sample is in "
            + "the export with its values and a hash, and the integrity check says valid")
    void sampleUpgradesToAValidChain() throws IOException {
        Upgraded upgraded = UPGRADED.get();
        Exported exported = EXPORTED.get();
        Db db = upgraded.stack().db();
        JsonNode expected = AuditFile.parse(Files.readString(sample("sample.expected.json"), StandardCharsets.UTF_8))
                .path("audit");

        assertThat(migrations(db)).as("successful migrations after the app started")
                .isGreaterThan(upgraded.migrationsBefore());
        assertThat(db.count("select count(*) from " + Db.quote(db.schema())
                + ".flyway_schema_history where not success")).as("failed migrations").isZero();
        assertThat(expected).as("audit rows in sample.expected.json").isNotEmpty();
        for (JsonNode row : expected) {
            JsonNode stored = AuditFile.parse(AuditFile.text(row, "row"));
            long id = AuditFile.number(row, "id");
            List<JsonNode> lines = exported.chain().kept().stream()
                    .filter(line -> AuditFile.number(line, "id") == id).toList();
            assertThat(lines).as("lines of the export with the sample's audit row %d", id).hasSize(1);
            JsonNode line = lines.getFirst();
            assertThat(AuditFile.text(line, "action")).as("action of row %d", id)
                    .isEqualTo(AuditFile.text(stored, "action"));
            for (String column : List.of("actor", "target", "changes")) {
                assertThat(AuditFile.canonical(line.path(column))).as("%s of row %d", column, id)
                        .isEqualTo(AuditFile.canonical(stored.path(column)));
            }
            assertThat(AuditFile.micros(AuditFile.text(line, "occurredAt"))).as("occurredAt of row %d", id)
                    .isEqualTo(AuditFile.micros(AuditFile.text(stored, "occurred_at")));
            assertThat(AuditFile.text(line, "hash")).as("hash of row %d", id).matches(AuditFile.HASH_PATTERN);
        }
        JsonNode answer = AuditFile.assertValid(exported.admin());
        assertThat(AuditFile.number(answer, "checkedCount")).as("checkedCount in %s", answer)
                .isGreaterThan(upgraded.rowsBefore().size());
    }

    @Test
    @DisplayName("S1B-AC-27 with -Xmx384m an export of more than " + BULK + " entries has every one of them, as "
            + "many lines as its own entry counts, and leaves the app running with no OutOfMemoryError")
    void exportOfAHundredThousandEntriesWithASmallHeap() {
        Upgraded upgraded = UPGRADED.get();
        Exported exported = EXPORTED.get();
        Stack stack = upgraded.stack();

        Set<Long> ids = new HashSet<>(exported.chain().ids());
        assertThat(ids.size()).as("different IDs among the %d lines of the export", exported.chain().ids().size())
                .isEqualTo(exported.chain().ids().size());
        assertThat(upgraded.rowsBefore().stream().filter(id -> !ids.contains(id)).limit(10).toList())
                .as("audit rows from before the upgrade that are not in the export (the first 10)").isEmpty();
        assertThat(exported.recordedCount()).as("count in the export's AUDIT_EXPORTED entry")
                .isEqualTo(exported.chain().ids().size());
        double heap = Metrics.samples(stack.prometheus(), "jvm_memory_max_bytes").stream()
                .filter(s -> "heap".equals(s.label("area")) && s.value() > 0).mapToDouble(Sample::value).sum();
        assertThat(heap).as("heap max from jvm_memory_max_bytes").isPositive().isLessThanOrEqualTo(MAX_HEAP);
        JsonNode state = stack.inspect("app").path("State");
        assertThat(state.path("OOMKilled").asBoolean(true)).as("app OOMKilled, state %s", state).isFalse();
        assertThat(state.path("Running").asBoolean(false)).as("app running, state %s", state).isTrue();
        assertThat(stack.inspect("app").path("RestartCount").asInt(-1)).as("app restart count").isZero();
        assertThat(stack.logs("app")).as("app log").doesNotContain("OutOfMemoryError");
    }

    @Test
    @DisplayName("S1B-AC-43 the DATABASE_RESTORED row that popdesk restore added to the 0.1.0 schema is in the "
            + "chain after the upgrade, with the system as its person, and the integrity check says valid")
    void restoreCommandRowFromTheOldSchemaIsChained() {
        Upgraded upgraded = UPGRADED.get();
        Exported exported = EXPORTED.get();

        List<JsonNode> restored = exported.chain().kept().stream()
                .filter(line -> "DATABASE_RESTORED".equals(line.path("action").asString(null))).toList();

        assertThat(restored).as("DATABASE_RESTORED lines of the export").hasSize(1);
        assertThat(AuditFile.number(restored.getFirst().path("actor"), "accountId")).as("the person of %s",
                restored.getFirst()).isZero();
        assertThat(upgraded.rowsBefore()).as("IDs of the audit rows before the app started")
                .contains(AuditFile.number(restored.getFirst(), "id"));
        AuditFile.assertValid(exported.admin());
    }
}
