package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.account;
import static io.popdesk.project.PeopleSteps.signInAttempt;
import static io.popdesk.project.ProjectChecks.allValues;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static io.popdesk.project.ProjectChecks.assertNoStore;
import static io.popdesk.project.ProjectChecks.pending;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * What an admin does to an account: rename, change the role, suspend and reactivate, reset the
 * password. Without SMTP, so the reset link comes in the response. Ending sessions and the reset
 * link itself are checked by the auth tests.
 */
class AccountAdminTest extends BareAppTest {

    private static final String NEW_PASSWORD = "changed-Password-2";

    /** An account that never accepted: {@code INVITED}, or {@code CANCELLED} (invited, then cancelled). */
    private Invitation neverAccepted(Session admin, String state) {
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        if (state.equals("CANCELLED")) {
            PeopleSteps.cancel(admin, invitation.accountId());
        }
        return invitation;
    }

    private void assertSignsIn(Session who, String password) {
        signInAttempt(client(who.app()), who.email(), password).expectStatus(204);
    }

    private void assertCannotSignIn(Session who, String password) {
        assertError(signInAttempt(client(who.app()), who.email(), password), 401, "SIGN_IN_FAILED");
    }

    // S1A-AC-30

    @ParameterizedTest(name = "accepted={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("S1A-AC-30 an admin renames an account, also one that has not accepted yet")
    void renames(boolean accepted) {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        if (accepted) {
            fixtures.accept(invitation);
        }
        String newName = Unique.text("Renamed");

        ApiResponse renamed = admin.client().patch(account(invitation.accountId())).json(Map.of("name", newName))
                .expect(200);

        assertThat(renamed.jsonLong("id")).isEqualTo(invitation.accountId());
        assertThat(renamed.jsonText("name")).isEqualTo(newName);
        assertThat(renamed.jsonText("email")).isEqualTo(invitation.email());
        assertThat(renamed.jsonText("status")).isEqualTo(accepted ? "ACTIVE" : "INVITED");
        assertThat(ProjectChecks.account(admin, invitation.accountId()).path("name").asString(null))
                .as("name in the account list").isEqualTo(newName);
        if (!accepted) {
            assertThat(pending(admin, invitation.email())).as("pending entries of the renamed account")
                    .extracting(n -> n.path("name").asString(null)).containsExactly(newName);
        }
    }

    @Test
    @DisplayName("S1A-AC-30 a name of 51 characters is 400 with field name TOO_LONG and keeps the name; 50 is saved")
    void nameLength() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String fifty = "n".repeat(50);

        ApiResponse tooLong = admin.client().patch(account(agent.accountId())).json(Map.of("name", fifty + "n"))
                .send();

        assertFieldError(tooLong, 400, "name", "TOO_LONG");
        assertThat(ProjectChecks.account(admin, agent.accountId()).path("name").asString(null))
                .isEqualTo(agent.name());
        assertThat(admin.client().patch(account(agent.accountId())).json(Map.of("name", fifty)).expect(200)
                .jsonText("name")).isEqualTo(fifty);
    }

    // S1A-AC-31

    @Test
    @DisplayName("S1A-AC-31 an agent made admin and agent again keeps the company assignment: listed as assigned, "
            + "and sees that company's issues again")
    void roleChangeKeepsAssignments() {
        Session admin = fixtures.signInAdmin();
        Company assigned = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Issue ofAssigned = fixtures.fileIssue(fixtures.customer(admin, assigned.code()), assigned.code());
        Issue ofOther = fixtures.fileIssue(fixtures.customer(admin, other.code()), other.code());
        Session agent = fixtures.agent(admin, assigned.code());

        ApiResponse promoted = PeopleSteps.roleChange(admin, agent.accountId(), Role.ADMIN).expect(200);

        assertThat(promoted.jsonLong("id")).isEqualTo(agent.accountId());
        assertThat(promoted.jsonText("role")).isEqualTo("ADMIN");
        assertThat(ProjectChecks.account(admin, agent.accountId()).path("role").asString(null)).isEqualTo("ADMIN");
        assertThat(PeopleSteps.members(admin, assigned.code(), "agents")).as("agents of the company after the "
                + "agent became an admin").contains(agent.accountId());
        Session asAdmin = fixtures.signIn(App.CONSOLE, agent.email(), agent.password());
        assertThat(asAdmin.role()).isEqualTo(Role.ADMIN);
        asAdmin.client().get("/api/console/issues/" + ofOther.key()).expect(200);

        ApiResponse demoted = PeopleSteps.roleChange(admin, agent.accountId(), Role.AGENT).expect(200);

        assertThat(demoted.jsonText("role")).isEqualTo("AGENT");
        Session asAgent = fixtures.signIn(App.CONSOLE, agent.email(), agent.password());
        assertThat(asAgent.role()).isEqualTo(Role.AGENT);
        assertThat(asAgent.client().get("/api/console/issues/" + ofAssigned.key()).expect(200).jsonText("key"))
                .isEqualTo(ofAssigned.key());
        assertThat(allValues(asAgent.client(), "/api/console/issues", "key")).contains(ofAssigned.key())
                .doesNotContain(ofOther.key());
        assertError(asAgent.client().get("/api/console/issues/" + ofOther.key()).send(), 404, "NOT_FOUND");
    }

    // S1A-AC-32

    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"CUSTOMER, AGENT", "CUSTOMER, ADMIN", "AGENT, CUSTOMER", "ADMIN, CUSTOMER"})
    @DisplayName("S1A-AC-32 making a customer staff, or staff a customer, is 400 CUSTOMER_ROLE_FIXED and changes "
            + "nothing")
    void customerRoleIsFixed(Role from, Role to) {
        Session admin = fixtures.signInAdmin();
        Session target = switch (from) {
            case CUSTOMER -> fixtures.customer(admin, fixtures.createCompany(admin).code());
            case AGENT -> fixtures.agent(admin);
            case ADMIN -> fixtures.admin(admin);
        };

        ApiResponse response = PeopleSteps.roleChange(admin, target.accountId(), to).send();

        assertError(response, 400, "CUSTOMER_ROLE_FIXED");
        assertThat(ProjectChecks.account(admin, target.accountId()).path("role").asString(null))
                .isEqualTo(from.name());
        target.client().get(target.api("/me")).expect(200);
    }

    // S1A-AC-33

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"suspend", "role"})
    @DisplayName("S1A-AC-33 an admin suspending or changing the role of their own account gets 400 OWN_ACCOUNT and "
            + "stays an active admin")
    void notOnOwnAccount(String action) {
        Session admin = fixtures.admin(fixtures.signInAdmin());

        ApiResponse response = action.equals("suspend")
                ? admin.client().post(account(admin.accountId()) + "/suspend").send()
                : PeopleSteps.roleChange(admin, admin.accountId(), Role.AGENT).send();

        assertError(response, 400, "OWN_ACCOUNT");
        JsonNode own = ProjectChecks.account(admin, admin.accountId());
        assertThat(own.path("role").asString(null)).isEqualTo("ADMIN");
        assertThat(own.path("status").asString(null)).isEqualTo("ACTIVE");
        assertSignsIn(admin, admin.password());
    }

    // S1A-AC-34

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CUSTOMER", "AGENT", "ADMIN"})
    @DisplayName("S1A-AC-34 suspending an ACTIVE account makes it SUSPENDED and it cannot sign in; suspending it "
            + "again is 200")
    void suspends(Role role) {
        Session admin = fixtures.signInAdmin();
        Session target = switch (role) {
            case CUSTOMER -> fixtures.customer(admin, fixtures.createCompany(admin).code());
            case AGENT -> fixtures.agent(admin);
            case ADMIN -> fixtures.admin(admin);
        };

        ApiResponse suspended = admin.client().post(account(target.accountId()) + "/suspend").expect(200);

        assertThat(suspended.jsonLong("id")).isEqualTo(target.accountId());
        assertThat(suspended.jsonText("status")).isEqualTo("SUSPENDED");
        assertThat(ProjectChecks.status(admin, target.accountId())).isEqualTo("SUSPENDED");
        assertCannotSignIn(target, target.password());

        ApiResponse again = admin.client().post(account(target.accountId()) + "/suspend").expect(200);

        assertThat(again.jsonText("status")).isEqualTo("SUSPENDED");
        assertCannotSignIn(target, target.password());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INVITED", "CANCELLED"})
    @DisplayName("S1A-AC-34 suspending an account that never accepted, or whose invitation was cancelled, is 400 "
            + "ACCOUNT_NOT_ACTIVE and changes nothing")
    void cannotSuspendBeforeAcceptance(String state) {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = neverAccepted(admin, state);

        ApiResponse response = admin.client().post(account(invitation.accountId()) + "/suspend").send();

        assertError(response, 400, "ACCOUNT_NOT_ACTIVE");
        assertThat(ProjectChecks.status(admin, invitation.accountId()))
                .isEqualTo(state.equals("INVITED") ? "INVITED" : "SUSPENDED");
        assertThat(pending(admin, invitation.email())).as("pending entries of the account")
                .extracting(n -> n.path("cancelled").asBoolean(false)).containsExactly(state.equals("CANCELLED"));
    }

    // S1A-AC-35

    @Test
    @DisplayName("S1A-AC-35 reactivating a suspended account makes it ACTIVE and it signs in with its password; "
            + "reactivating an ACTIVE account is 200")
    void reactivates() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        PeopleSteps.suspend(admin, agent.accountId());

        ApiResponse reactivated = admin.client().post(account(agent.accountId()) + "/reactivate").expect(200);

        assertThat(reactivated.jsonLong("id")).isEqualTo(agent.accountId());
        assertThat(reactivated.jsonText("status")).isEqualTo("ACTIVE");
        assertThat(ProjectChecks.status(admin, agent.accountId())).isEqualTo("ACTIVE");
        assertSignsIn(agent, agent.password());

        ApiResponse again = admin.client().post(account(agent.accountId()) + "/reactivate").expect(200);

        assertThat(again.jsonText("status")).isEqualTo("ACTIVE");
        assertSignsIn(agent, agent.password());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INVITED", "CANCELLED"})
    @DisplayName("S1A-AC-35 reactivating an account that never accepted its invitation is 400 "
            + "INVITATION_NOT_ACCEPTED, and its link stays as it was")
    void cannotReactivateBeforeAcceptance(String state) {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = neverAccepted(admin, state);

        ApiResponse response = admin.client().post(account(invitation.accountId()) + "/reactivate").send();

        assertError(response, 400, "INVITATION_NOT_ACCEPTED");
        assertThat(ProjectChecks.status(admin, invitation.accountId()))
                .isEqualTo(state.equals("INVITED") ? "INVITED" : "SUSPENDED");
        ApiResponse accept = console().post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", Fixtures.PASSWORD)).send();
        if (state.equals("INVITED")) {
            accept.expectStatus(204);
        } else {
            assertError(accept, 400, "INVITATION_INVALID");
        }
    }

    // S1A-AC-36, S1A-AC-37

    @Test
    @DisplayName("S1A-AC-36 after an admin resets a password the old one no longer signs in, and the account can "
            + "still be suspended and reactivated")
    void resetEndsTheOldPassword() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);

        PeopleSteps.resetPassword(admin, agent.accountId());

        assertCannotSignIn(agent, agent.password());
        assertThat(ProjectChecks.status(admin, agent.accountId())).isEqualTo("ACTIVE");
        assertThat(PeopleSteps.suspend(admin, agent.accountId()).jsonText("status")).isEqualTo("SUSPENDED");
        assertThat(PeopleSteps.reactivate(admin, agent.accountId()).jsonText("status")).isEqualTo("ACTIVE");
        assertError(admin.client().post(PeopleSteps.invitation(agent.accountId(), "resend")).send(), 400,
                "INVITATION_ALREADY_ACCEPTED");
    }

    @Test
    @DisplayName("S1A-AC-36, S1A-AC-37 resetting the password lifts a sign-in lock: without SMTP the response "
            + "carries the link, with Cache-Control no-store, and the new password signs in at once")
    void resetLiftsTheLock() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        ApiClient browser = console();
        for (int i = 0; i < 5; i++) {
            assertError(signInAttempt(browser, agent.email(), "wrong-Password-" + i), 401, "SIGN_IN_FAILED");
        }
        assertCannotSignIn(agent, agent.password());

        ApiResponse reset = PeopleSteps.resetPassword(admin, agent.accountId());
        PeopleSteps.confirmReset(console(), reset.jsonText("resetLink"), NEW_PASSWORD).expectStatus(204);

        assertNoStore(reset);
        assertSignsIn(agent, NEW_PASSWORD);
        assertCannotSignIn(agent, agent.password());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INVITED", "CANCELLED", "SUSPENDED"})
    @DisplayName("S1A-AC-36 resetting the password of an account that is not ACTIVE is 400 ACCOUNT_NOT_ACTIVE")
    void cannotResetUnlessActive(String state) {
        Session admin = fixtures.signInAdmin();
        long accountId;
        Session suspended = null;
        if (state.equals("SUSPENDED")) {
            suspended = fixtures.agent(admin);
            PeopleSteps.suspend(admin, suspended.accountId());
            accountId = suspended.accountId();
        } else {
            accountId = neverAccepted(admin, state).accountId();
        }

        ApiResponse response = admin.client().post(account(accountId) + "/password-reset").send();

        assertError(response, 400, "ACCOUNT_NOT_ACTIVE");
        assertThat(ProjectChecks.status(admin, accountId)).isEqualTo(state.equals("INVITED") ? "INVITED" : "SUSPENDED");
        if (suspended != null) {
            PeopleSteps.reactivate(admin, accountId);
            assertSignsIn(suspended, suspended.password());
        }
    }

    @Test
    @DisplayName("S1A-AC-37 resetting again gives another link at once, also within 15 minutes of the account's "
            + "own request for one, and the earlier link stops working")
    void resetAgainReplacesTheLink() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        console().post("/api/public/password-reset/request").json(Map.of("email", agent.email())).expect(202);

        String first = PeopleSteps.resetPassword(admin, agent.accountId()).jsonText("resetLink");
        String second = PeopleSteps.resetPassword(admin, agent.accountId()).jsonText("resetLink");

        assertThat(Fixtures.tokenOf(second)).as("token of the second reset link").isNotEqualTo(Fixtures.tokenOf(first));
        assertError(PeopleSteps.confirmReset(console(), first, NEW_PASSWORD), 400, "PASSWORD_RESET_INVALID");
        assertCannotSignIn(agent, NEW_PASSWORD);
        PeopleSteps.confirmReset(console(), second, NEW_PASSWORD).expectStatus(204);
        assertSignsIn(agent, NEW_PASSWORD);
    }
}
