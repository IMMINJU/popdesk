package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.columnsContaining;
import static io.popdesk.project.ProjectChecks.hashForms;
import static io.popdesk.project.ProjectChecks.status;
import static io.popdesk.project.ProjectChecks.tokenBytes;
import static io.popdesk.project.ProjectChecks.tokenForms;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The invitation token: where it sits in the link, what the database keeps, 7 days, once. */
class InvitationTokenTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-35 the token is only in the link's fragment, not in its path or query")
    void tokenOnlyInFragment() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, company.code());
        Invitation agent = fixtures.invite(admin, Role.AGENT);

        for (Invitation invitation : new Invitation[] {customer, agent}) {
            URI link = URI.create(invitation.link());
            assertThat(link.getScheme()).isEqualTo("http");
            assertThat(link.getPort()).isEqualTo(-1);
            assertThat(link.getRawPath()).isEqualTo("/invite");
            assertThat(link.getRawQuery()).isNull();
            assertThat(link.getRawFragment()).isEqualTo("token=" + invitation.token());
        }
        assertThat(URI.create(customer.link()).getHost()).isEqualTo(PORTAL_HOST);
        assertThat(URI.create(agent.link()).getHost()).isEqualTo(CONSOLE_HOST);
    }

    @Test
    @DisplayName("S0-AC-35 the token stands for 32 random bytes and each invitation gets its own")
    void tokenIs32Bytes() {
        Session admin = fixtures.signInAdmin();
        Invitation one = fixtures.invite(admin, Role.AGENT);
        Invitation two = fixtures.invite(admin, Role.AGENT);

        assertThat(tokenBytes(one.token())).as("token %s as base64url or hex", one.token())
                .hasValueSatisfying(b -> assertThat(b).hasSize(32));
        assertThat(tokenBytes(two.token())).hasValueSatisfying(b -> assertThat(b).hasSize(32));
        assertThat(one.token()).isNotEqualTo(two.token());
    }

    @Test
    @DisplayName("S0-AC-35 the database keeps the token's SHA-256 and never the token itself")
    void databaseKeepsOnlyHash() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        assertThat(columnsContaining(db, tokenForms(invitation.token()))).isEmpty();
        assertThat(columnsContaining(db, hashForms(invitation.token()))).isNotEmpty();
    }

    @Test
    @DisplayName("S0-AC-35 a token works until 7 days after the invitation and not after")
    void tokenExpiresAfterSevenDays() {
        Session admin = fixtures.signInAdmin();
        Invitation early = fixtures.invite(admin, Role.AGENT);
        Invitation late = fixtures.invite(admin, Role.AGENT);

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        accept(early.token(), CONSOLE_HOST).expectStatus(204);

        clock.advance(Duration.ofMinutes(2));
        assertError(accept(late.token(), CONSOLE_HOST), 400, "INVITATION_INVALID");

        Session again = fixtures.signInAdmin();
        assertThat(status(again, early.accountId())).isEqualTo("ACTIVE");
        assertThat(status(again, late.accountId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("S0-AC-35 a token works once: a second accept is 400 and keeps the first password")
    void tokenWorksOnce() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        accept(invitation.token(), CONSOLE_HOST).expectStatus(204);

        ApiResponse second = console().post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", "another-Password-2"))
                .send();

        assertError(second, 400, "INVITATION_INVALID");
        fixtures.signIn(Fixtures.App.CONSOLE, invitation.email(), Fixtures.PASSWORD);
        assertError(console().post("/api/public/sign-in")
                .json(Map.of("email", invitation.email(), "password", "another-Password-2"))
                .send(), 401, "SIGN_IN_FAILED");
    }

    private ApiResponse accept(String token, String host) {
        return client(host).post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", Fixtures.PASSWORD))
                .send();
    }
}
