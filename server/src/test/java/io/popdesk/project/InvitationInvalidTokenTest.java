package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.status;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tokens that do not work: unknown, expired, used, replaced by a newer link, or sent to the other
 * app's host. All get the same 400 body and change no account.
 */
class InvitationInvalidTokenTest extends BareAppTest {

    private static final String TRIED = "tried-Password-9";

    @Test
    @DisplayName("S0-AC-37 an unknown token is 400 INVITATION_INVALID")
    void unknownToken() {
        assertError(accept(randomToken(), CONSOLE_HOST), 400, "INVITATION_INVALID");
        assertError(accept(randomToken(), PORTAL_HOST), 400, "INVITATION_INVALID");
    }

    @Test
    @DisplayName("S0-AC-37 an expired token is 400 INVITATION_INVALID and the account stays INVITED")
    void expiredToken() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        clock.advance(Duration.ofDays(7).plusMinutes(1));

        assertError(accept(invitation.token(), CONSOLE_HOST), 400, "INVITATION_INVALID");

        assertThat(status(fixtures.signInAdmin(), invitation.accountId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("S0-AC-37 a used token is 400 INVITATION_INVALID and the password stays the first one")
    void usedToken() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        fixtures.accept(invitation);

        assertError(accept(invitation.token(), CONSOLE_HOST), 400, "INVITATION_INVALID");

        assertThat(status(admin, invitation.accountId())).isEqualTo("ACTIVE");
        fixtures.signIn(App.CONSOLE, invitation.email(), Fixtures.PASSWORD);
        assertError(signIn(CONSOLE_HOST, invitation.email(), TRIED), 401, "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("S0-AC-37 a token replaced by a copied link is 400 INVITATION_INVALID and the new link still works")
    void replacedToken() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        String newToken = Fixtures.tokenOf(admin.client()
                .post("/api/console/invitations/" + invitation.accountId() + "/link")
                .expect(200)
                .jsonText("inviteLink"));

        assertError(accept(invitation.token(), CONSOLE_HOST), 400, "INVITATION_INVALID");

        assertThat(status(admin, invitation.accountId())).isEqualTo("INVITED");
        accept(newToken, CONSOLE_HOST).expectStatus(204);
    }

    @Test
    @DisplayName("S0-AC-37 a customer token on the console host and a staff token on the portal host are 400 and change nothing")
    void tokenOnOtherAppsHost() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, company.code());
        Invitation agent = fixtures.invite(admin, Role.AGENT);

        assertError(accept(customer.token(), CONSOLE_HOST), 400, "INVITATION_INVALID");
        assertError(accept(agent.token(), PORTAL_HOST), 400, "INVITATION_INVALID");

        assertThat(status(admin, customer.accountId())).isEqualTo("INVITED");
        assertThat(status(admin, agent.accountId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("S0-AC-37 all five cases answer with the same 400 body")
    void sameBodyForAllCases() {
        Session admin = fixtures.signInAdmin();
        Invitation toExpire = fixtures.invite(admin, Role.AGENT);
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation used = fixtures.invite(admin, Role.AGENT);
        fixtures.accept(used);
        Invitation replaced = fixtures.invite(admin, Role.AGENT);
        admin.client().post("/api/console/invitations/" + replaced.accountId() + "/link").expect(200);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, company.code());

        Map<String, ApiResponse> responses = new LinkedHashMap<>();
        responses.put("unknown", accept(randomToken(), CONSOLE_HOST));
        responses.put("expired", accept(toExpire.token(), CONSOLE_HOST));
        responses.put("used", accept(used.token(), CONSOLE_HOST));
        responses.put("replaced", accept(replaced.token(), CONSOLE_HOST));
        responses.put("other host", accept(customer.token(), CONSOLE_HOST));

        JsonNode first = responses.get("unknown").json();
        responses.forEach((what, response) -> {
            assertError(response, 400, "INVITATION_INVALID");
            assertThat(response.json()).as(what).isEqualTo(first);
        });
    }

    private ApiResponse accept(String token, String host) {
        return client(host).post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", TRIED))
                .send();
    }

    private ApiResponse signIn(String host, String email, String password) {
        return client(host).post("/api/public/sign-in").json(Map.of("email", email, "password", password)).send();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
