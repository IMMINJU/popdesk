package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.PasswordSteps.Refused;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Setting a new password with the token of a reset link (AUTH-7): what a good token does, and that
 * every token that does not count gets one and the same 400.
 */
class PasswordResetConfirmTest extends ConnectedAppTest {

    private static final String ATTEMPTED = "attempted-Password-3";

    /** How a password comes to be reset. */
    enum Reset {
        BY_AN_ADMIN,
        WITH_A_RESET_LINK
    }

    /** Tokens that do not count. */
    enum Invalid {
        NEVER_ISSUED,
        OLDER_THAN_AN_HOUR,
        ALREADY_USED,
        REPLACED_BY_A_NEWER_REQUEST,
        ON_THE_OTHER_APPS_HOST,
        INVITATION_TOKEN,
        ACCOUNT_SUSPENDED_SINCE
    }

    /**
     * One try with a token that does not count: where it is sent, whose account it is about (null for
     * nobody's), the password that account must still sign in with (null when it cannot sign in), and
     * what must still work after the refusal.
     */
    private record Attempt(App host, String token, String email, App app, String keptPassword, Runnable stillWorks) {

        Attempt(App host, String token, String email, App app, String keptPassword) {
            this(host, token, email, app, keptPassword, () -> {});
        }
    }

    @Test
    @DisplayName("S1A-AC-09 a new password set with the link's token replaces the old one and lifts the sign-in lock, "
            + "without signing in")
    void tokenSetsThePasswordAndLiftsTheLock() {
        Session customer = newCustomer();
        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());
        // S1A-AC-10: the token counts for an hour, so it still does one minute before.
        clock.advance(Duration.ofMinutes(59));
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertError(AuthChecks.signIn(portal(), customer.email(), "wrong-Password-" + attempt,
                    AuthChecks.freshIp()), 401, "SIGN_IN_FAILED");
        }
        ApiClient browser = portal();

        ApiResponse done = PasswordSteps.confirm(browser, token, PasswordSteps.NEW_PASSWORD);

        done.expectStatus(204);
        assertThat(AuthChecks.sessionCookies(done)).as("session cookies set by the reset").isEmpty();
        assertError(browser.get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
        assertError(AuthChecks.signIn(portal(), customer.email(), customer.password(), AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        AuthChecks.signIn(browser, customer.email(), PasswordSteps.NEW_PASSWORD, AuthChecks.freshIp())
                .expectStatus(204);
        assertThat(browser.get("/api/portal/me").expect(200).jsonLong("id")).isEqualTo(customer.accountId());
    }

    @ParameterizedTest
    @EnumSource(Reset.class)
    @DisplayName("S1A-AC-04 a password reset by an admin or with a reset link ends every session of the account")
    void resetEndsEverySession(Reset how) {
        Session admin = fixtures.signInAdmin();
        Session first = fixtures.customer(admin, fixtures.createCompany(admin).code());
        Session second = fixtures.signInAgain(first);
        first.client().get("/api/portal/me").expect(200);
        second.client().get("/api/portal/me").expect(200);

        switch (how) {
            case BY_AN_ADMIN -> admin.client()
                    .post("/api/console/accounts/" + first.accountId() + "/password-reset").expect(200);
            case WITH_A_RESET_LINK -> PasswordSteps.confirm(portal(),
                    PasswordSteps.requestToken(portal(), mail(), first.email()), PasswordSteps.NEW_PASSWORD)
                    .expectStatus(204);
        }

        assertError(first.client().get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
        assertError(second.client().get("/api/portal/me").send(), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @EnumSource(Invalid.class)
    @DisplayName("S1A-AC-10, S1A-AC-12, S1A-AC-83, S1A-AC-84 a token that does not count gets the same 400 "
            + "PASSWORD_RESET_INVALID as one never issued, and no password changes")
    void tokenThatDoesNotCountIsRefusedAlike(Invalid which) {
        Attempt attempt = arrange(which);

        ApiResponse neverIssued = PasswordSteps.confirm(client(attempt.host()), PasswordSteps.tokenNeverIssued(),
                ATTEMPTED);
        ApiResponse refused = PasswordSteps.confirm(client(attempt.host()), attempt.token(), ATTEMPTED);

        assertError(refused, 400, "PASSWORD_RESET_INVALID");
        assertThat(refused.body()).as("body for %s vs body for a token never issued", which)
                .isEqualTo(neverIssued.body());
        if (attempt.email() != null) {
            assertError(AuthChecks.signIn(client(attempt.app()), attempt.email(), ATTEMPTED, AuthChecks.freshIp()),
                    401, "SIGN_IN_FAILED");
        }
        if (attempt.keptPassword() != null) {
            AuthChecks.signIn(client(attempt.app()), attempt.email(), attempt.keptPassword(), AuthChecks.freshIp())
                    .expectStatus(204);
        }
        attempt.stillWorks().run();
    }

    @ParameterizedTest(name = "token field present: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("S1A-AC-10 a request without a token field, or with an empty one, gets 400 INVALID_INPUT with "
            + "token REQUIRED, not the answer for a token that does not count, and no password changes")
    void missingOrEmptyTokenIsInvalidInput(boolean fieldPresent) {
        Session customer = newCustomer();
        Map<String, String> body = fieldPresent
                ? Map.of("token", "", "password", ATTEMPTED)
                : Map.of("password", ATTEMPTED);

        ApiResponse refused = portal().post(PasswordSteps.CONFIRM).json(body).send();

        assertError(refused, 400, "INVALID_INPUT");
        assertThat(AuthChecks.fieldErrors(refused)).contains("token:REQUIRED");
        assertError(AuthChecks.signIn(portal(), customer.email(), ATTEMPTED, AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        AuthChecks.signIn(portal(), customer.email(), customer.password(), AuthChecks.freshIp()).expectStatus(204);
    }

    @Test
    @DisplayName("S1A-AC-83 a reset token sent to the invitation accept API gets the same 400 as an unknown "
            + "invitation token and changes no password")
    void resetTokenIsNoInvitationToken() {
        Session customer = newCustomer();
        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());

        ApiResponse unknown = accept(PasswordSteps.tokenNeverIssued());
        ApiResponse refused = accept(token);

        assertError(refused, 400, "INVITATION_INVALID");
        assertThat(refused.body()).as("body for a reset token vs body for an unknown token").isEqualTo(unknown.body());
        assertThat(AuthChecks.sessionCookies(refused)).as("session cookies set by the refused accept").isEmpty();
        assertError(AuthChecks.signIn(portal(), customer.email(), ATTEMPTED, AuthChecks.freshIp()),
                401, "SIGN_IN_FAILED");
        AuthChecks.signIn(portal(), customer.email(), customer.password(), AuthChecks.freshIp()).expectStatus(204);
    }

    @ParameterizedTest
    @EnumSource(Refused.class)
    @DisplayName("S1A-AC-11 a new password against AUTH-5 gets 400 and leaves the token unused")
    void refusedPasswordKeepsTheToken(Refused rule) {
        Session customer = newCustomer();
        String token = PasswordSteps.requestToken(portal(), mail(), customer.email());

        ApiResponse refused = PasswordSteps.confirm(portal(), token, rule.password(customer.email()));

        assertError(refused, 400, "INVALID_INPUT");
        assertThat(AuthChecks.fieldErrors(refused)).contains("password:" + rule.name());
        PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
        AuthChecks.signIn(portal(), customer.email(), PasswordSteps.NEW_PASSWORD, AuthChecks.freshIp())
                .expectStatus(204);
    }

    private Session newCustomer() {
        Session admin = fixtures.signInAdmin();
        return fixtures.customer(admin, fixtures.createCompany(admin).code());
    }

    private ApiResponse accept(String token) {
        return portal().post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", ATTEMPTED))
                .send();
    }

    private Attempt arrange(Invalid which) {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        if (which == Invalid.NEVER_ISSUED) {
            return new Attempt(App.PORTAL, PasswordSteps.tokenNeverIssued(), null, App.PORTAL, null);
        }
        if (which == Invalid.INVITATION_TOKEN) {
            Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, code);
            return new Attempt(App.PORTAL, invitation.token(), invitation.email(), App.PORTAL, null,
                    () -> accept(invitation.token()).expectStatus(204));
        }
        Session customer = fixtures.customer(admin, code);
        String email = customer.email();
        String token = PasswordSteps.requestToken(portal(), mail(), email);
        return switch (which) {
            case OLDER_THAN_AN_HOUR -> {
                clock.advance(Duration.ofMinutes(61));
                yield new Attempt(App.PORTAL, token, email, App.PORTAL, Fixtures.PASSWORD);
            }
            case ALREADY_USED -> {
                PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204);
                yield new Attempt(App.PORTAL, token, email, App.PORTAL, PasswordSteps.NEW_PASSWORD);
            }
            case REPLACED_BY_A_NEWER_REQUEST -> {
                clock.advance(Duration.ofMinutes(16));
                PasswordSteps.request(portal(), email).expectStatus(202);
                PasswordSteps.awaitMails(mail(), email, 2);
                yield new Attempt(App.PORTAL, token, email, App.PORTAL, Fixtures.PASSWORD);
            }
            case ON_THE_OTHER_APPS_HOST -> new Attempt(App.CONSOLE, token, email, App.PORTAL, Fixtures.PASSWORD,
                    () -> PasswordSteps.confirm(portal(), token, PasswordSteps.NEW_PASSWORD).expectStatus(204));
            case ACCOUNT_SUSPENDED_SINCE -> {
                admin.client().post("/api/console/accounts/" + customer.accountId() + "/suspend").expect(200);
                yield new Attempt(App.PORTAL, token, email, App.PORTAL, null, () -> {
                    admin.client().post("/api/console/accounts/" + customer.accountId() + "/reactivate")
                            .expect(200);
                    assertError(AuthChecks.signIn(portal(), email, ATTEMPTED, AuthChecks.freshIp()),
                            401, "SIGN_IN_FAILED");
                    AuthChecks.signIn(portal(), email, Fixtures.PASSWORD, AuthChecks.freshIp()).expectStatus(204);
                });
            }
            case NEVER_ISSUED, INVITATION_TOKEN -> throw new IllegalStateException(which.name());
        };
    }
}
