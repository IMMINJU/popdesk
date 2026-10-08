package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertNoStore;
import static io.popdesk.project.ProjectChecks.columnsContaining;
import static io.popdesk.project.ProjectChecks.tokenForms;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Without SMTP an admin copies a new invitation link from the pending list. */
class InvitationLinkCopyTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-38 copying a link makes a new token: the old link stops working and the new one works")
    void copyMakesNewToken() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());

        ApiResponse copy = copy(admin, invitation.accountId());

        copy.expectStatus(200);
        String link = copy.jsonText("inviteLink");
        assertThat(link).startsWith("http://" + PORTAL_HOST + "/invite#token=");
        String token = Fixtures.tokenOf(link);
        assertThat(token).isNotEqualTo(invitation.token());
        assertError(accept(invitation.token(), PORTAL_HOST), 400, "INVITATION_INVALID");
        accept(token, PORTAL_HOST).expectStatus(204);
    }

    @Test
    @DisplayName("S0-AC-38 copying twice leaves only the last link working; a staff link points at the console")
    void lastCopyWins() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        String first = Fixtures.tokenOf(copy(admin, invitation.accountId()).expectStatus(200).jsonText("inviteLink"));
        String secondLink = copy(admin, invitation.accountId()).expectStatus(200).jsonText("inviteLink");
        String second = Fixtures.tokenOf(secondLink);

        assertThat(secondLink).startsWith("http://" + CONSOLE_HOST + "/invite#token=");
        assertThat(second).isNotEqualTo(first);
        assertError(accept(first, CONSOLE_HOST), 400, "INVITATION_INVALID");
        accept(second, CONSOLE_HOST).expectStatus(204);
    }

    @Test
    @DisplayName("S0-AC-38 the copy response has Cache-Control no-store")
    void copyIsNotCached() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        ApiResponse copy = copy(admin, invitation.accountId());

        copy.expectStatus(200);
        assertNoStore(copy);
    }

    @Test
    @DisplayName("S0-AC-38 the link is given only in that response: lists and the database never hold it")
    void linkOnlyInResponse() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        String link = copy(admin, invitation.accountId()).expectStatus(200).jsonText("inviteLink");
        String token = Fixtures.tokenOf(link);

        for (String path : List.of("/api/console/invitations", "/api/console/accounts")) {
            String body = admin.client().get(path).query("size", 100).expect(200).text();
            assertThat(body).as(path).doesNotContain(token).doesNotContain(invitation.token())
                    .doesNotContain("#token=");
        }
        List<String> needles = new ArrayList<>(tokenForms(token));
        needles.addAll(tokenForms(invitation.token()));
        needles.add("#token=");
        assertThat(columnsContaining(db, needles)).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-38 copying is 404 for an accepted or unknown account and 403 for an agent")
    void copyOnlyForPendingByAdmin() {
        Session admin = fixtures.signInAdmin();
        Invitation pendingOne = fixtures.invite(admin, Role.AGENT);
        Session accepted = fixtures.agent(admin);

        assertError(copy(admin, accepted.accountId()), 404, "NOT_FOUND");
        assertError(copy(admin, 999_999_999L), 404, "NOT_FOUND");
        assertError(copy(accepted, pendingOne.accountId()), 403, "FORBIDDEN");

        accept(pendingOne.token(), CONSOLE_HOST).expectStatus(204);
    }

    private static ApiResponse copy(Session session, long accountId) {
        return session.client().post("/api/console/invitations/" + accountId + "/link").send();
    }

    private ApiResponse accept(String token, String host) {
        return client(host).post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", Fixtures.PASSWORD))
                .send();
    }
}
