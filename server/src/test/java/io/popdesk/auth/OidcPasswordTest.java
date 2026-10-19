package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * OIDC sign-in next to the password: an invited account becomes active by OIDC without a password (S11-AC-18), the
 * password lock does not stop OIDC and OIDC does not touch its count (S11-AC-19), an account without a password
 * gets the usual failed password sign-in and can set one by a reset mail (S11-AC-22), and is an accepted account to
 * reactivate (S11-AC-23).
 */
class OidcPasswordTest extends OidcAppTest {

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "AGENT"})
    @DisplayName("S11-AC-18 an invited account within 7 days signs in by OIDC on its app and is ACTIVE, its "
            + "invitation link then answers 400 INVITATION_INVALID, and password sign-in answers 401")
    void invitedAccountBecomesActive(Role role) {
        Session admin = fixtures.signInAdmin();
        String[] companies = role == Role.CUSTOMER ? new String[] {fixtures.createCompany(admin).code()}
                : new String[0];
        Invitation invited = fixtures.invite(admin, role, companies);
        ApiClient browser = client(role.app());

        long signedIn = oidc.signedIn(browser, role.app().api(), OidcFlow.newSub(), oidc.claims(invited.email()));

        assertThat(signedIn).as("account signed in").isEqualTo(invited.accountId());
        assertThat(account(admin, invited.accountId()).path("status").asString()).as("status after the sign-in")
                .isEqualTo("ACTIVE");
        assertError(client(role.app()).post("/api/public/invitations/accept")
                .json(Map.of("token", invited.token(), "password", Fixtures.PASSWORD)).send(), 400,
                "INVITATION_INVALID");
        assertError(PasswordSteps.signIn(client(role.app()), invited.email(), Fixtures.PASSWORD), 401,
                "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("S11-AC-19 an account locked by five wrong passwords signs in by OIDC, and the right password still "
            + "gets 401 after it")
    void lockedAccountSignsInByOidc() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertError(PasswordSteps.signIn(console(), agent.email(), "wrong-Password-" + attempt), 401,
                    "SIGN_IN_FAILED");
        }
        assertError(PasswordSteps.signIn(console(), agent.email(), agent.password()), 401, "SIGN_IN_FAILED");

        long signedIn = oidc.signedIn(console(), "/api/console", OidcFlow.newSub(), oidc.claims(agent.email()));

        assertThat(signedIn).as("account signed in by OIDC").isEqualTo(agent.accountId());
        assertError(PasswordSteps.signIn(console(), agent.email(), agent.password()), 401, "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("S11-AC-19 an OIDC sign-in after four wrong passwords keeps their count: one more wrong password "
            + "locks the account")
    void oidcKeepsTheCountOfWrongPasswords() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertError(PasswordSteps.signIn(console(), agent.email(), "wrong-Password-" + attempt), 401,
                    "SIGN_IN_FAILED");
        }
        oidc.signedIn(console(), "/api/console", OidcFlow.newSub(), oidc.claims(agent.email()));

        assertError(PasswordSteps.signIn(console(), agent.email(), "wrong-Password-5"), 401, "SIGN_IN_FAILED");

        assertError(PasswordSteps.signIn(console(), agent.email(), agent.password()), 401, "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("S11-AC-22 an account made active by OIDC gets the same 401 for a password sign-in as an unknown "
            + "email, sets a password from a reset mail, and then signs in both ways")
    void accountWithoutPasswordSetsOneByReset() {
        Session admin = fixtures.signInAdmin();
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        String sub = OidcFlow.newSub();
        oidc.signedIn(console(), "/api/console", sub, oidc.claims(invited.email()));

        ApiResponse noPassword = PasswordSteps.signIn(console(), invited.email(), Fixtures.PASSWORD);
        ApiResponse unknown = PasswordSteps.signIn(console(), Unique.email("nobody"), Fixtures.PASSWORD);

        assertError(noPassword, 401, "SIGN_IN_FAILED");
        assertThat(noPassword.body()).as("body next to an unknown email's").isEqualTo(unknown.body());
        String token = PasswordSteps.requestToken(console(), mail(), invited.email());
        PasswordSteps.confirm(console(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
        PasswordSteps.signIn(console(), invited.email(), PasswordSteps.NEW_PASSWORD).expectStatus(204);
        assertThat(oidc.signedIn(console(), "/api/console", sub, oidc.claims(invited.email())))
                .as("OIDC sign-in after the password was set").isEqualTo(invited.accountId());
    }

    @Test
    @DisplayName("S11-AC-23 an account made active by OIDC, suspended, and reactivated answers 200 and is ACTIVE "
            + "again, and signs in by OIDC")
    void reactivatingAnOidcAccountWorks() {
        Session admin = fixtures.signInAdmin();
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        String sub = OidcFlow.newSub();
        oidc.signedIn(console(), "/api/console", sub, oidc.claims(invited.email()));
        admin.client().post(ACCOUNTS + "/" + invited.accountId() + "/suspend").expect(200);

        ApiResponse reactivated = admin.client().post(ACCOUNTS + "/" + invited.accountId() + "/reactivate").send();

        assertThat(reactivated.status()).as("%s: %s", reactivated, reactivated.snippet()).isEqualTo(200);
        assertThat(account(admin, invited.accountId()).path("status").asString()).as("status after reactivating")
                .isEqualTo("ACTIVE");
        assertThat(oidc.signedIn(console(), "/api/console", sub, oidc.claims(invited.email())))
                .as("OIDC sign-in after reactivating").isEqualTo(invited.accountId());
    }
}
