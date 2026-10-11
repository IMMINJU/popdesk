package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.PasswordSteps.Refused;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Changing one's own password ({@code PUT .../me/password}, ACCT-2). Wrong current passwords count
 * with the wrong passwords of sign-in: five in a row block this request and the account's sign-in
 * for 15 minutes (design.md section 3).
 */
class OwnPasswordTest extends BareAppTest {

    @ParameterizedTest
    @EnumSource(App.class)
    @DisplayName("S1A-AC-17 with the current password and a new one that AUTH-5 accepts, the new one signs in "
            + "and the old one does not")
    void passwordIsChanged(App app) {
        Session me = person(app);

        change(me, me.password(), PasswordSteps.NEW_PASSWORD).expectStatus(204);

        assertError(AuthChecks.signIn(client(app), me.email(), me.password(), AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        AuthChecks.signIn(client(app), me.email(), PasswordSteps.NEW_PASSWORD, AuthChecks.freshIp())
                .expectStatus(204);
    }

    @ParameterizedTest
    @EnumSource(Refused.class)
    @DisplayName("S1A-AC-17 a new password against AUTH-5 gets 400 and the password stays")
    void newPasswordAgainstTheRulesIsRefused(Refused rule) {
        Session me = person(App.PORTAL);

        ApiResponse refused = change(me, me.password(), rule.password(me.email()));

        assertError(refused, 400, "INVALID_INPUT");
        assertThat(AuthChecks.fieldErrors(refused)).contains("newPassword:" + rule.name());
        AuthChecks.signIn(portal(), me.email(), me.password(), AuthChecks.freshIp()).expectStatus(204);
    }

    @Test
    @DisplayName("S1A-AC-18 a wrong current password gets 400 CURRENT_PASSWORD_WRONG and the password stays")
    void wrongCurrentPasswordIsRefused() {
        Session me = person(App.PORTAL);

        ApiResponse refused = change(me, "wrong-Password-1", PasswordSteps.NEW_PASSWORD);

        assertError(refused, 400, "CURRENT_PASSWORD_WRONG");
        assertError(AuthChecks.signIn(portal(), me.email(), PasswordSteps.NEW_PASSWORD, AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        AuthChecks.signIn(portal(), me.email(), me.password(), AuthChecks.freshIp()).expectStatus(204);
    }

    @Test
    @DisplayName("S1A-AC-18 after five wrong current passwords in a row the request gets 429 with Retry-After for "
            + "15 minutes, even with the right one, and the account's sign-in is locked too")
    void fiveWrongCurrentPasswordsBlockForFifteenMinutes() {
        Session me = person(App.PORTAL);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertError(change(me, "wrong-Password-" + attempt, PasswordSteps.NEW_PASSWORD),
                    400, "CURRENT_PASSWORD_WRONG");
        }

        ApiResponse blocked = change(me, me.password(), PasswordSteps.NEW_PASSWORD);

        assertError(blocked, 429, "RATE_LIMITED");
        String retryAfter = blocked.header("Retry-After");
        assertThat(retryAfter).as("Retry-After of %s", blocked).isNotNull().matches("\\d+");
        long seconds = Long.parseLong(retryAfter);
        assertThat(seconds).as("seconds left of the 15 minutes").isBetween(1L, 900L);
        assertError(AuthChecks.signIn(portal(), me.email(), me.password(), AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        clock.advance(Duration.ofSeconds(seconds - 1));
        assertError(change(me, me.password(), PasswordSteps.NEW_PASSWORD), 429, "RATE_LIMITED");
        clock.advance(Duration.ofSeconds(1));
        change(me, me.password(), PasswordSteps.NEW_PASSWORD).expectStatus(204);
        AuthChecks.signIn(portal(), me.email(), PasswordSteps.NEW_PASSWORD, AuthChecks.freshIp())
                .expectStatus(204);
    }

    private static ApiResponse change(Session me, String currentPassword, String newPassword) {
        return me.client().put(me.api("/me/password"))
                .json(Map.of("currentPassword", currentPassword, "newPassword", newPassword))
                .send();
    }

    private Session person(App app) {
        Session admin = fixtures.signInAdmin();
        return app == App.PORTAL
                ? fixtures.customer(admin, fixtures.createCompany(admin).code())
                : fixtures.accept(fixtures.invite(admin, Role.AGENT));
    }
}
