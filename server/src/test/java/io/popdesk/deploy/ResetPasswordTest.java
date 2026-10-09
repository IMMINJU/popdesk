package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code popdesk admin reset-password <email>} inside the app container (ACCT-6, ADR D-46,
 * configuration.md "Commands"). With a terminal ({@link Terminal}: a Docker exec with a TTY, as
 * {@code docker compose exec app ...} without {@code -T}), the command asks twice for the new
 * password. The cases that must change nothing run first; the reset itself runs last. "Nothing
 * changed" means every business-table row that holds the account's email is the same as before,
 * the password hash included.
 */
@Tag("deploy")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResetPasswordTest {

    private static final String NEW_PASSWORD = "reset-Password-7q";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The installation, with an agent account besides the first admin. */
    record Installation(Stack stack, Db db, String agentEmail) {}

    private static final Once<Installation> INSTALLATION = new Once<>("starting an installation",
            ResetPasswordTest::install);

    @AfterAll
    static void stop() {
        Installation installation = INSTALLATION.peek();
        if (installation != null) {
            installation.stack().close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("S0-AC-97 for an email with no account, the command fails and changes nothing")
    void unknownEmail() {
        Installation installation = INSTALLATION.get();
        List<String> before = rowsWith(installation, Stack.ADMIN_EMAIL);

        Terminal.Session session = reset(installation, "nobody-97@popdesk.test", NEW_PASSWORD);

        assertThat(session.exit()).as("exit code\n%s", session.output()).isPositive();
        assertThat(rowsWith(installation, Stack.ADMIN_EMAIL)).as("rows with the admin's email").isEqualTo(before);
        assertThat(installation.db().rowsContaining("ADMIN_PASSWORD_RESET")).as("ADMIN_PASSWORD_RESET rows").isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("S0-AC-97 for an account that is not ADMIN, the command fails and changes nothing")
    void notAnAdmin() {
        Installation installation = INSTALLATION.get();
        List<String> before = rowsWith(installation, installation.agentEmail());

        Terminal.Session session = reset(installation, installation.agentEmail(), NEW_PASSWORD);

        assertThat(session.exit()).as("exit code\n%s", session.output()).isPositive();
        assertThat(rowsWith(installation, installation.agentEmail())).as("rows with the agent's email").isEqualTo(before);
        assertThat(installation.db().rowsContaining("ADMIN_PASSWORD_RESET")).as("ADMIN_PASSWORD_RESET rows").isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("S0-AC-97 a new password that breaks the password rules (9 characters) fails and changes nothing")
    void passwordBreaksRules() {
        Installation installation = INSTALLATION.get();
        List<String> before = rowsWith(installation, Stack.ADMIN_EMAIL);

        Terminal.Session session = reset(installation, Stack.ADMIN_EMAIL, "short-Pw9");

        assertThat(session.exit()).as("exit code (a command still waiting for input is -1)\n%s", session.output())
                .isPositive();
        assertThat(rowsWith(installation, Stack.ADMIN_EMAIL)).as("rows with the admin's email").isEqualTo(before);
        assertThat(installation.db().rowsContaining("ADMIN_PASSWORD_RESET")).as("ADMIN_PASSWORD_RESET rows").isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("S0-AC-97 without a terminal (exec -T), the command fails and changes nothing")
    void noTerminal() {
        Installation installation = INSTALLATION.get();
        List<String> before = rowsWith(installation, Stack.ADMIN_EMAIL);
        byte[] typed = (NEW_PASSWORD + "\n" + NEW_PASSWORD + "\n").getBytes(StandardCharsets.UTF_8);

        Cli.Result result = installation.stack().exec("app", typed, "popdesk", "admin", "reset-password",
                Stack.ADMIN_EMAIL);

        assertThat(result.exit()).as("exit code\n%s", result).isPositive();
        assertThat(rowsWith(installation, Stack.ADMIN_EMAIL)).as("rows with the admin's email").isEqualTo(before);
        assertThat(installation.db().rowsContaining("ADMIN_PASSWORD_RESET")).as("ADMIN_PASSWORD_RESET rows").isEmpty();
        new AppSteps(installation.stack()).signIn(Stack.ADMIN_EMAIL, Stack.ADMIN_PASSWORD);
    }

    @Test
    @Order(5)
    @DisplayName("S0-AC-97 with a terminal, the new password typed twice signs in, the lock is lifted, old sessions "
            + "end, and the audit row has the system as its person; the password is never printed or logged")
    void resetsWithTerminal() {
        Installation installation = INSTALLATION.get();
        Stack stack = installation.stack();
        Person old = new AppSteps(stack).signIn(Stack.ADMIN_EMAIL, Stack.ADMIN_PASSWORD);
        for (int i = 0; i < 5; i++) {
            signIn(stack, Stack.ADMIN_EMAIL, "wrong-Password-" + i).expectStatus(401);
        }
        signIn(stack, Stack.ADMIN_EMAIL, Stack.ADMIN_PASSWORD).expectStatus(401);

        Terminal.Session session = reset(installation, Stack.ADMIN_EMAIL, NEW_PASSWORD);

        assertThat(session.exit()).as("exit code\n%s", session.output()).isZero();
        assertThat(session.output()).as("command output").doesNotContain(NEW_PASSWORD);
        old.client().get("/api/console/me").expect(401);
        ApiClient fresh = stack.console();
        fresh.post("/api/public/sign-in").json(Map.of("email", Stack.ADMIN_EMAIL, "password", NEW_PASSWORD)).expect(204);
        fresh.get("/api/console/me").expect(200);
        List<Db.Row> audit = installation.db().rowsContaining("ADMIN_PASSWORD_RESET");
        assertThat(audit).as("ADMIN_PASSWORD_RESET rows").hasSize(1);
        assertThat(audit.getFirst().hasPerson(0)).as("person {accountId: 0} in %s", audit.getFirst()).isTrue();
        assertThat(audit.getFirst().text()).as("audit row").doesNotContain(NEW_PASSWORD);
        assertThat(stack.logs("app")).as("app log").doesNotContain(NEW_PASSWORD);
    }

    @Test
    @Order(6)
    @DisplayName("S0-AC-97 neither contract file has an HTTP path for resetting a password")
    void noHttpPath() throws IOException {
        for (String app : List.of("portal", "console")) {
            String text = Files.readString(Repo.require("docs/integrate/openapi-" + app + ".json", "contract file"),
                    StandardCharsets.UTF_8);
            List<String> paths = new ArrayList<>();
            JSON.readTree(text).path("paths").propertyNames().forEach(paths::add);

            assertThat(paths).as("paths of openapi-%s.json", app).isNotEmpty()
                    .noneMatch(p -> p.toLowerCase().contains("password"));
        }
    }

    private static Installation install() {
        Stack stack = Stack.release("reset");
        try {
            stack.up();
            stack.awaitHealthy("app");
            AppSteps steps = new AppSteps(stack);
            Person admin = steps.signInAdmin();
            Person agent = steps.join(admin, "AGENT", List.of(), null);
            return new Installation(stack, stack.db(), agent.email());
        } catch (AssertionError | RuntimeException e) {
            stack.close();
            throw e;
        }
    }

    private static Terminal.Session reset(Installation installation, String email, String password) {
        return Terminal.run(installation.stack().containerId("app"),
                List.of("popdesk", "admin", "reset-password", email), List.of(password, password));
    }

    private static ApiResponse signIn(Stack stack, String email, String password) {
        return stack.console().post("/api/public/sign-in").json(Map.of("email", email, "password", password)).send();
    }

    /** Business-table rows that hold the email, as JSON text, sorted. */
    private static List<String> rowsWith(Installation installation, String email) {
        return installation.db().rowsContaining(email).stream().map(Db.Row::toString).sorted().toList();
    }
}
