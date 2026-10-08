package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUTH-5 on accepting an invitation: 10 to 72 characters (code points), not only whitespace, not the
 * email; stored as Argon2id. The first admin's password follows the same rules: see
 * {@link FirstAdminPasswordRejectedBase} and the accepted lengths in {@link HttpsPublicAddressTest}
 * (10) and {@link UntrustedProxyTest} (72).
 */
class PasswordRulesTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-28 an accepted invitation stores the password as Argon2id, never in plain text")
    void acceptedPasswordIsArgon2id() {
        Invitation invitation = newInvitation(Role.CUSTOMER);
        String password = "Plain-" + Unique.number() + "-password";

        accept(invitation, password).expectStatus(204);

        List<Map<String, Object>> rows = AuthChecks.rowsContaining(db, invitation.email());
        assertThat(rows).as("rows holding %s", invitation.email()).isNotEmpty();
        assertThat(rows.stream().flatMap(r -> r.values().stream()).filter(Objects::nonNull).map(Object::toString))
                .as("an Argon2id hash in a row of %s", invitation.email())
                .anyMatch(v -> v.startsWith("$argon2id$"));
        assertThat(AuthChecks.rowsContaining(db, password)).as("rows holding the plain password").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-28 the first admin's password is stored as Argon2id")
    void firstAdminPasswordIsArgon2id() {
        AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);

        List<Map<String, Object>> rows = AuthChecks.rowsContaining(db, AppTest.ADMIN_EMAIL);
        assertThat(rows.stream().flatMap(r -> r.values().stream()).filter(Objects::nonNull).map(Object::toString))
                .as("an Argon2id hash in a row of %s", AppTest.ADMIN_EMAIL)
                .anyMatch(v -> v.startsWith("$argon2id$"));
        assertThat(AuthChecks.rowsContaining(db, AppTest.ADMIN_PASSWORD)).as("rows holding the plain password")
                .isEmpty();
    }

    @Test
    @DisplayName("S0-AC-28 accepting with a 10-character password works")
    void tenCharacters() {
        acceptsAndSignsIn(newInvitation(Role.CUSTOMER), "Ten-chars1");
    }

    @Test
    @DisplayName("S0-AC-28 accepting with a 72-character password works")
    void seventyTwoCharacters() {
        acceptsAndSignsIn(newInvitation(Role.AGENT), "P".repeat(36) + "-".repeat(35) + "1");
    }

    @Test
    @DisplayName("S0-AC-28 password length counts code points: 72 Hangul characters work")
    void seventyTwoHangulCharacters() {
        acceptsAndSignsIn(newInvitation(Role.CUSTOMER), "비밀번호".repeat(18));
    }

    @Test
    @DisplayName("S0-AC-28 accepting with a 9-character password is 400 TOO_SHORT")
    void nineCharacters() {
        rejected(newInvitation(Role.CUSTOMER), "Nine-ch1x", "TOO_SHORT");
    }

    @Test
    @DisplayName("S0-AC-28 accepting with a 73-character password is 400 TOO_LONG")
    void seventyThreeCharacters() {
        rejected(newInvitation(Role.AGENT), "P".repeat(36) + "-".repeat(36) + "1", "TOO_LONG");
    }

    @Test
    @DisplayName("S0-AC-28 accepting with a password of only whitespace is 400 BLANK")
    void onlyWhitespace() {
        rejected(newInvitation(Role.CUSTOMER), " ".repeat(12), "BLANK");
    }

    @Test
    @DisplayName("S0-AC-28 accepting with the account's email as password is 400 SAME_AS_EMAIL")
    void sameAsEmail() {
        Invitation invitation = newInvitation(Role.AGENT);
        rejected(invitation, invitation.email(), "SAME_AS_EMAIL");
    }

    private Invitation newInvitation(Role role) {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        return role == Role.CUSTOMER
                ? fixtures.invite(admin, role, fixtures.createCompany(admin).code())
                : fixtures.invite(admin, role);
    }

    private ApiResponse accept(Invitation invitation, String password) {
        return client(invitation.role().app()).post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", password))
                .send();
    }

    private void acceptsAndSignsIn(Invitation invitation, String password) {
        assertThat(password.codePointCount(0, password.length())).isBetween(10, 72);

        accept(invitation, password).expectStatus(204);

        ApiClient browser = client(invitation.role().app());
        AuthChecks.signIn(browser, invitation.email(), password, AuthChecks.freshIp()).expectStatus(204);
        assertThat(browser.get(invitation.role().app().api() + "/me").expect(200).jsonLong("id"))
                .isEqualTo(invitation.accountId());
    }

    private void rejected(Invitation invitation, String password, String fieldCode) {
        ApiResponse response = accept(invitation, password);

        assertError(response, 400, "INVALID_INPUT");
        assertThat(AuthChecks.fieldErrors(response)).contains("password:" + fieldCode);
        assertThat(AuthChecks.sessionCookies(response)).isEmpty();
        if (password.isBlank()) {
            return;
        }
        assertError(AuthChecks.signIn(client(invitation.role().app()), invitation.email(), password,
                AuthChecks.freshIp()), 401, "SIGN_IN_FAILED");
    }
}
