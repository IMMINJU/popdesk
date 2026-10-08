package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertFieldError;
import static io.popdesk.project.ProjectChecks.pending;
import static io.popdesk.project.ProjectChecks.status;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Accepting an invitation makes the account ACTIVE and signs it in to its own app. */
class InvitationAcceptTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-36 a customer who accepts is ACTIVE and signed in to the portal")
    void customerAccepts() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());
        ApiClient portal = portal();

        ApiResponse accepted = accept(portal, invitation.token(), Fixtures.PASSWORD);

        accepted.expectStatus(204);
        ApiResponse me = portal.get("/api/portal/me").expect(200);
        assertThat(me.jsonLong("id")).isEqualTo(invitation.accountId());
        assertThat(me.jsonText("email")).isEqualTo(invitation.email());
        assertThat(me.jsonText("name")).isEqualTo(invitation.name());
        assertThat(status(admin, invitation.accountId())).isEqualTo("ACTIVE");
        assertThat(pending(admin, invitation.email())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-36 an agent and an admin who accept are ACTIVE and signed in to the console")
    void staffAccept() {
        Session admin = fixtures.signInAdmin();
        for (Role role : new Role[] {Role.AGENT, Role.ADMIN}) {
            Invitation invitation = fixtures.invite(admin, role);
            ApiClient console = console();

            accept(console, invitation.token(), Fixtures.PASSWORD).expectStatus(204);

            ApiResponse me = console.get("/api/console/me").expect(200);
            assertThat(me.jsonLong("id")).isEqualTo(invitation.accountId());
            assertThat(me.jsonText("role")).isEqualTo(role.name());
            assertThat(status(admin, invitation.accountId())).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("S0-AC-36 the password set on accepting signs the person in later")
    void passwordWorksForSignIn() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());
        accept(portal(), invitation.token(), "accepted-Password-3").expectStatus(204);

        Session later = fixtures.signIn(Fixtures.App.PORTAL, invitation.email(), "accepted-Password-3");

        assertThat(later.accountId()).isEqualTo(invitation.accountId());
    }

    @Test
    @DisplayName("S0-AC-36 a password that breaks AUTH-5 is 400 on password and the account stays INVITED")
    void weakPasswordKeepsInvited() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        ApiClient console = console();

        assertFieldError(accept(console, invitation.token(), "short-Pw1"), 400, "password", "TOO_SHORT");

        assertThat(status(admin, invitation.accountId())).isEqualTo("INVITED");
        assertThat(console.get("/api/console/me").send().status()).isEqualTo(401);
    }

    private static ApiResponse accept(ApiClient client, String token, String password) {
        return client.post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", password))
                .send();
    }
}
