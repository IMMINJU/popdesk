package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.AuditChainMath;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The commands inside the app container that leave audit rows without going through the app
 * ({@code popdesk backup}, {@code popdesk admin reset-password}), run while the app is up: their
 * rows join the same hash chain (slice 1b premortem section 3), and a backup made here, restored
 * into a second installation by {@code popdesk restore}. The restore command on a dump of the 0.1.0
 * schema is checked by {@link UpgradeAuditChainTest}.
 *
 * <p>The installation has the outside copy on, at an address where nothing listens: a copy stays in
 * the send queue, where the test finds it by the {@code outbox} columns {@code channel} and
 * {@code audit_id} (design section 1). The tests share the installation, so each looks only at the
 * lines after a company it created first.
 */
@Tag("deploy")
class CommandAuditChainTest {

    private static final String NEW_PASSWORD = "chain-Password-7q";

    /** Nothing listens on port 9 inside the app container, so a copy sent there fails and stays queued. */
    private static final String COPY_URL = "http://127.0.0.1:9/audit-copy";

    private static final List<String> COMMAND_KINDS = List.of("BACKUP_CREATED", "ADMIN_PASSWORD_RESET");

    private static final Once<Stack> RUNNING = new Once<>("starting an installation", CommandAuditChainTest::start);

    @AfterAll
    static void stop() {
        Stack stack = RUNNING.peek();
        if (stack != null) {
            stack.close();
        }
    }

    private static Stack start() {
        Stack stack = Stack.release("commandchain").appEnv("AUDIT_FORWARD_URL", COPY_URL);
        try {
            stack.up();
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    /** The lines of an export from the {@code COMPANY_CREATED} line of this company on. */
    private static List<JsonNode> from(List<JsonNode> lines, String companyCode) {
        for (int i = 0; i < lines.size(); i++) {
            JsonNode line = lines.get(i);
            if ("COMPANY_CREATED".equals(line.path("action").asString(null))
                    && companyCode.equals(line.path("target").path("code").asString(null))) {
                return lines.subList(i, lines.size());
            }
        }
        throw new AssertionError("No COMPANY_CREATED line of " + companyCode + " in the export");
    }

    private static List<String> actions(List<JsonNode> lines) {
        return lines.stream().map(line -> AuditChainMath.text(line, "action")).toList();
    }

    @Test
    @DisplayName("S1B-AC-43, S1B-AC-29 popdesk backup and popdesk admin reset-password, run while the app is up, "
            + "leave entries that are in the chain with a hash, between the app's own entries, and the integrity "
            + "check says valid; with the outside copy on, each of them has a copy row in the send queue")
    void commandEntriesJoinTheChainWhileTheAppRuns() {
        Stack stack = RUNNING.get();
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        Person other = steps.join(admin, "ADMIN", List.of(), null);
        String first = steps.createCompany(admin);

        Cli.Result backup = stack.exec("app", null, "popdesk", "backup", "/tmp/popdesk-chain-test.dump");
        steps.createCompany(admin);
        Terminal.Session reset = Terminal.run(stack.containerId("app"),
                List.of("popdesk", "admin", "reset-password", other.email()), List.of(NEW_PASSWORD, NEW_PASSWORD));
        String company = steps.createCompany(admin);

        assertThat(backup.exit()).as("popdesk backup\n%s", backup).isZero();
        assertThat(reset.exit()).as("popdesk admin reset-password\n%s", reset.output()).isZero();
        AuditFile.Chain chain = AuditFile.export(admin, line -> true);
        List<JsonNode> mine = from(chain.kept(), first);
        List<String> actions = actions(mine);
        assertThat(actions.stream().filter("BACKUP_CREATED"::equals).count()).as("BACKUP_CREATED lines in %s",
                actions).isEqualTo(1);
        assertThat(actions.stream().filter("ADMIN_PASSWORD_RESET"::equals).count())
                .as("ADMIN_PASSWORD_RESET lines in %s", actions).isEqualTo(1);
        List<JsonNode> byCommands = mine.stream()
                .filter(line -> COMMAND_KINDS.contains(AuditChainMath.text(line, "action"))).toList();
        for (JsonNode line : byCommands) {
            assertThat(AuditChainMath.number(line.path("actor"), "accountId")).as("the person of %s", line).isZero();
            assertThat(AuditChainMath.text(line, "hash")).as("hash of %s", line).matches(AuditChainMath.HASH_PATTERN);
        }
        assertThat(actions.indexOf("BACKUP_CREATED")).as("place of BACKUP_CREATED in %s", actions)
                .isLessThan(actions.indexOf("ADMIN_PASSWORD_RESET"));
        assertThat(AuditChainMath.text(mine.getLast().path("target"), "code"))
                .as("the last line: the company created after the commands").isEqualTo(company);
        assertThat(chain.firstSeq()).as("seq of the first line of the export").isEqualTo(1);
        assertThat(chain.firstPrevHash()).as("prevHash of the first line").isEqualTo(AuditChainMath.ZERO_HASH);
        AuditChainMath.assertValid(admin.client());
        Db db = stack.db();
        Set<Long> queued = db.query("select audit_id from " + Db.quote(db.schema()) + ".outbox "
                        + "where channel = 'AUDIT_FORWARD' and audit_id is not null").stream()
                .map(row -> ((Number) row.get("audit_id")).longValue()).collect(Collectors.toSet());
        assertThat(queued).as("audit entries with a copy row in the send queue")
                .containsAll(byCommands.stream().map(line -> AuditChainMath.number(line, "id")).toList());
    }

    @Test
    @DisplayName("S1B-AC-43 a backup of this image, put into a new installation by popdesk restore, starts with "
            + "its chain whole: the DATABASE_RESTORED entry follows the restored entries with the system as its "
            + "person, and the integrity check says valid")
    void restoredBackupKeepsItsChainAndTheRestoreEntryFollowsIt() {
        Stack stack = RUNNING.get();
        AppSteps steps = new AppSteps(stack);
        String company = steps.createCompany(steps.signInAdmin());
        String inApp = "/tmp/popdesk-chain-restore-test.dump";
        Cli.Result backup = stack.exec("app", null, "popdesk", "backup", inApp);
        assertThat(backup.exit()).as("popdesk backup\n%s", backup).isZero();
        Path dump = stack.work().resolve("chain-restore.dump");
        stack.command("cp", "app:" + inApp, dump.toString()).expectOk("copy the dump out of app");
        dump.toFile().setReadable(true, false);

        try (Stack restored = Stack.release("commandrestore")) {
            restored.up("db");
            restored.db().awaitReady();
            restored.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                            Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore",
                            "/tmp/restore.dump")
                    .expectOk("popdesk restore of a backup of this image");
            restored.up("app");
            restored.awaitHealthy("app");
            Person admin = new AppSteps(restored).signInAdmin();

            AuditFile.Chain chain = AuditFile.export(admin, line -> true);

            List<String> actions = actions(from(chain.kept(), company));
            assertThat(actions.stream().filter("DATABASE_RESTORED"::equals).count())
                    .as("DATABASE_RESTORED lines after the company made before the backup, in %s", actions)
                    .isEqualTo(1);
            assertThat(actions.subList(actions.indexOf("DATABASE_RESTORED"), actions.size()))
                    .as("lines from DATABASE_RESTORED on: the sign-in after the restore follows it")
                    .contains("SIGN_IN_SUCCEEDED");
            JsonNode entry = chain.kept().stream()
                    .filter(line -> "DATABASE_RESTORED".equals(AuditChainMath.text(line, "action"))).findFirst()
                    .orElseThrow(() -> new AssertionError("No DATABASE_RESTORED line in the export"));
            assertThat(AuditChainMath.number(entry.path("actor"), "accountId")).as("the person of %s", entry).isZero();
            assertThat(AuditChainMath.text(entry, "hash")).as("hash of %s", entry)
                    .matches(AuditChainMath.HASH_PATTERN);
            assertThat(chain.firstSeq()).as("seq of the first line of the export").isEqualTo(1);
            assertThat(chain.firstPrevHash()).as("prevHash of the first line").isEqualTo(AuditChainMath.ZERO_HASH);
            AuditChainMath.assertValid(admin.client());
        }
    }
}
