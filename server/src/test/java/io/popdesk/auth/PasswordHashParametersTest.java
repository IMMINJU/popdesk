package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Argon2id parameters of a stored password (verify.md V-15, AUTH-5): 19 MiB of memory (19,456
 * KiB), 2 iterations, 1 lane, the least OWASP recommends. An Argon2 hash names its parameters at its
 * start, so the account row shows them.
 */
class PasswordHashParametersTest extends BareAppTest {

    private static final String PARAMETERS = "$argon2id$v=19$m=19456,t=2,p=1$";

    @Test
    @DisplayName("V-15 S0-AC-28 a password set by accepting an invitation is hashed with Argon2id m=19456, t=2, p=1")
    void acceptedPassword() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.accept(fixtures.invite(admin, Role.AGENT));

        assertThat(passwordHashes(agent.email())).as("password hashes in the rows of %s", agent.email())
                .hasSize(1)
                .allMatch(hash -> hash.startsWith(PARAMETERS), "starts with " + PARAMETERS);
    }

    @Test
    @DisplayName("V-15 S0-AC-28 the first admin's password is hashed with Argon2id m=19456, t=2, p=1")
    void firstAdminPassword() {
        AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);

        assertThat(passwordHashes(AppTest.ADMIN_EMAIL)).as("password hashes in the rows of %s", AppTest.ADMIN_EMAIL)
                .hasSize(1)
                .allMatch(hash -> hash.startsWith(PARAMETERS), "starts with " + PARAMETERS);
    }

    /** Values that are an Argon2 hash, in any row that holds the email. */
    private List<String> passwordHashes(String email) {
        return AuthChecks.rowsContaining(db, email).stream()
                .flatMap(row -> row.values().stream())
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(value -> value.startsWith("$argon2"))
                .toList();
    }
}
