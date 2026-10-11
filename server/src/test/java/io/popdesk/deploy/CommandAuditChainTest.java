package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The commands inside the app container that leave audit rows without going through the app
 * ({@code popdesk backup}, {@code popdesk admin reset-password}), run while the app is up: their
 * rows join the same hash chain (slice 1b premortem section 3). The restore command, which writes
 * to a database the app has not started on yet, is checked by {@link UpgradeAuditChainTest}.
 */
@Tag("deploy")
class CommandAuditChainTest {

    private static final String NEW_PASSWORD = "chain-Password-7q";

    private static final Once<Stack> RUNNING = new Once<>("starting an installation", CommandAuditChainTest::start);

    @AfterAll
    static void stop() {
        Stack stack = RUNNING.peek();
        if (stack != null) {
            stack.close();
        }
    }

    private static Stack start() {
        Stack stack = Stack.release("commandchain");
        try {
            stack.up();
            stack.awaitHealthy("app");
            return stack;
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    @Test
    @DisplayName("S1B-AC-43 popdesk backup and popdesk admin reset-password, run while the app is up, leave "
            + "entries that are in the chain with a hash, between the app's own entries, and the integrity "
            + "check says valid")
    void commandEntriesJoinTheChainWhileTheAppRuns() {
        Stack stack = RUNNING.get();
        AppSteps steps = new AppSteps(stack);
        Person admin = steps.signInAdmin();
        Person other = steps.join(admin, "ADMIN", List.of(), null);

        Cli.Result backup = stack.exec("app", null, "popdesk", "backup", "/tmp/popdesk-chain-test.dump");
        steps.createCompany(admin);
        Terminal.Session reset = Terminal.run(stack.containerId("app"),
                List.of("popdesk", "admin", "reset-password", other.email()), List.of(NEW_PASSWORD, NEW_PASSWORD));
        String company = steps.createCompany(admin);

        assertThat(backup.exit()).as("popdesk backup\n%s", backup).isZero();
        assertThat(reset.exit()).as("popdesk admin reset-password\n%s", reset.output()).isZero();
        AuditFile.Chain chain = AuditFile.export(admin, line -> true);
        List<String> actions = chain.kept().stream().map(line -> AuditFile.text(line, "action")).toList();
        assertThat(actions.stream().filter("BACKUP_CREATED"::equals).count()).as("BACKUP_CREATED lines in %s",
                actions).isEqualTo(1);
        assertThat(actions.stream().filter("ADMIN_PASSWORD_RESET"::equals).count())
                .as("ADMIN_PASSWORD_RESET lines in %s", actions).isEqualTo(1);
        for (JsonNode line : chain.kept()) {
            if (List.of("BACKUP_CREATED", "ADMIN_PASSWORD_RESET").contains(AuditFile.text(line, "action"))) {
                assertThat(AuditFile.number(line.path("actor"), "accountId")).as("the person of %s", line).isZero();
                assertThat(AuditFile.text(line, "hash")).as("hash of %s", line).matches(AuditFile.HASH_PATTERN);
            }
        }
        assertThat(actions.indexOf("BACKUP_CREATED")).as("place of BACKUP_CREATED in %s", actions)
                .isLessThan(actions.indexOf("ADMIN_PASSWORD_RESET"));
        assertThat(AuditFile.text(chain.kept().getLast().path("target"), "code"))
                .as("the last line: the company created after the commands").isEqualTo(company);
        assertThat(chain.firstSeq()).as("seq of the first line of the export").isEqualTo(1);
        assertThat(chain.firstPrevHash()).as("prevHash of the first line").isEqualTo(AuditFile.ZERO_HASH);
        AuditFile.assertValid(admin);
    }
}
