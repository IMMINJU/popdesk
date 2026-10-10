package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Argon2id parameters of a password set with {@code popdesk admin reset-password} (verify.md
 * V-15): the same as for every other stored password, 19 MiB of memory (19,456 KiB), 2 iterations,
 * 1 lane. An installation of its own, since the reset changes the admin's password.
 */
@Tag("deploy")
class ResetPasswordHashTest {

    private static final String PARAMETERS = "$argon2id$v=19$m=19456,t=2,p=1$";
    private static final String NEW_PASSWORD = "reset-Password-15v";
    private static final Pattern HASH = Pattern.compile("\\$argon2[a-z]*\\$[^\"\\\\]*");

    @Test
    @DisplayName("V-15 S0-AC-97 a password set by popdesk admin reset-password is hashed with Argon2id m=19456, t=2, p=1")
    void resetPasswordHash() {
        try (Stack stack = Stack.release("reset-hash")) {
            stack.up();
            stack.awaitHealthy("app");
            new AppSteps(stack).signInAdmin();

            Terminal.Session session = Terminal.run(stack.containerId("app"),
                    List.of("popdesk", "admin", "reset-password", Stack.ADMIN_EMAIL), List.of(NEW_PASSWORD, NEW_PASSWORD));

            assertThat(session.exit()).as("exit code\n%s", session.output()).isZero();
            stack.console().post("/api/public/sign-in")
                    .json(Map.of("email", Stack.ADMIN_EMAIL, "password", NEW_PASSWORD)).expect(204);
            List<String> hashes = stack.db().rowsContaining(Stack.ADMIN_EMAIL).stream()
                    .flatMap(row -> HASH.matcher(row.text()).results().map(MatchResult::group))
                    .toList();
            assertThat(hashes).as("password hashes in the rows of %s", Stack.ADMIN_EMAIL)
                    .hasSize(1)
                    .allMatch(hash -> hash.startsWith(PARAMETERS), "starts with " + PARAMETERS);
        }
    }
}
