package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertNoStore;
import static io.popdesk.project.ProjectChecks.status;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Inviting with SMTP: the invitation goes out as mail, and the copy-link endpoint is not there. */
class InvitationWithSmtpTest extends ConnectedAppTest {

    @Test
    @DisplayName("S0-AC-33 with SMTP an invitation queues one mail to the invited person and the response has no link")
    void invitationQueuesMail() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String email = Unique.email("mailed");

        ApiResponse response = InvitationTest.invite(admin, email, "Mailed", "CUSTOMER", List.of(company.code()));

        response.expectStatus(201);
        assertThat(response.json().has("inviteLink")).isTrue();
        assertThat(response.json().path("inviteLink").isNull()).isTrue();
        assertNoStore(response);
        String link = Fixtures.linkIn(mail().awaitMessageTo(email).text());
        assertThat(link).startsWith("http://" + PORTAL_HOST + "/invite#token=");
        Wait.holds("only one invitation mail to " + email, Duration.ofSeconds(3),
                () -> mail().messagesTo(email).size() == 1);
    }

    @Test
    @DisplayName("S0-AC-33 with SMTP a rejected invitation sends no mail")
    void rejectedInvitationSendsNoMail() {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("rejected");
        Session existing = fixtures.agent(admin);
        mail().reset();

        assertError(InvitationTest.invite(admin, email, "No company", "CUSTOMER", List.of()), 400, "INVALID_INPUT");
        assertError(InvitationTest.invite(admin, existing.email(), "Again", "AGENT", List.of()), 409, "EMAIL_TAKEN");

        Wait.holds("no mail for rejected invitations", Duration.ofSeconds(3),
                () -> mail().messagesTo(email).isEmpty() && mail().messagesTo(existing.email()).isEmpty());
    }

    @Test
    @DisplayName("S0-AC-39 with SMTP the copy-link endpoint is 404 NOT_FOUND and the mailed link still works")
    void copyLinkIsNotThere() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        ApiResponse copy = admin.client().post("/api/console/invitations/" + invitation.accountId() + "/link").send();

        assertError(copy, 404, "NOT_FOUND");
        assertThat(copy.text()).doesNotContain("#token=");
        console().post("/api/public/invitations/accept")
                .json(Map.of("token", invitation.token(), "password", Fixtures.PASSWORD))
                .expect(204);
        assertThat(status(admin, invitation.accountId())).isEqualTo("ACTIVE");
    }
}
