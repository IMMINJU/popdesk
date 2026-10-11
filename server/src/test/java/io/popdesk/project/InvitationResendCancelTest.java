package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.invitation;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertNoStore;
import static io.popdesk.project.ProjectChecks.pending;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * Sending an invitation again and cancelling it, without SMTP: the new link comes in the response,
 * as when inviting. The mail side is in {@link PeopleMailTest}.
 */
class InvitationResendCancelTest extends BareAppTest {

    private ApiResponse accept(Role role, String token) {
        ApiClient browser = client(role.app());
        return browser.post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", Fixtures.PASSWORD)).send();
    }

    private static JsonNode single(Session admin, String email) {
        List<JsonNode> entries = pending(admin, email);
        assertThat(entries).as("pending entries for " + email).hasSize(1);
        return entries.getFirst();
    }

    @Test
    @DisplayName("S1A-AC-38 sending again gives a new link in the response without SMTP, ends the old one, and the "
            + "new one works for 7 days from then")
    void resendReplacesTheLink() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        clock.advance(Duration.ofDays(1));
        admin = fixtures.signInAdmin();
        Instant resentAt = clock.instant();

        ApiResponse resent = admin.client().post(invitation(invitation.accountId(), "resend")).expect(200);

        assertNoStore(resent);
        assertThat(resent.jsonLong("accountId")).isEqualTo(invitation.accountId());
        String newToken = Fixtures.tokenOf(resent.jsonText("inviteLink"));
        assertThat(newToken).isNotEqualTo(invitation.token());
        JsonNode entry = single(admin, invitation.email());
        assertThat(Instant.parse(entry.path("expiresAt").asString(""))).isEqualTo(resentAt.plus(Duration.ofDays(7)));
        assertThat(entry.path("expired").asBoolean(true)).isFalse();
        assertError(accept(Role.AGENT, invitation.token()), 400, "INVITATION_INVALID");
        assertThat(ProjectChecks.status(admin, invitation.accountId())).isEqualTo("INVITED");

        clock.advance(Duration.ofDays(7).minusMinutes(1));

        accept(Role.AGENT, newToken).expectStatus(204);
    }

    @Test
    @DisplayName("S1A-AC-38 a link sent again is past its time 7 days after it was sent again")
    void resentLinkExpires() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        String newToken = Fixtures.tokenOf(PeopleSteps.resend(admin, invitation.accountId()).jsonText("inviteLink"));

        clock.advance(Duration.ofDays(7).plusMinutes(1));

        assertError(accept(Role.AGENT, newToken), 400, "INVITATION_INVALID");
    }

    @Test
    @DisplayName("S1A-AC-39 cancelling an invitation makes the account SUSPENDED and its link stop working")
    void cancelEndsTheLink() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        ApiResponse cancelled = admin.client().post(invitation(invitation.accountId(), "cancel")).expect(204);

        assertThat(cancelled.body()).as("body of the 204").isEmpty();
        assertThat(ProjectChecks.status(admin, invitation.accountId())).isEqualTo("SUSPENDED");
        assertError(accept(Role.AGENT, invitation.token()), 400, "INVITATION_INVALID");
        assertError(PeopleSteps.signInAttempt(console(), invitation.email(), Fixtures.PASSWORD), 401,
                "SIGN_IN_FAILED");
    }

    @Test
    @DisplayName("S1A-AC-40 sending a cancelled invitation again makes the account INVITED, and the new link is "
            + "accepted")
    void resendRevivesACancelledInvitation() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        PeopleSteps.cancel(admin, invitation.accountId());

        ApiResponse resent = admin.client().post(invitation(invitation.accountId(), "resend")).expect(200);

        String newToken = Fixtures.tokenOf(resent.jsonText("inviteLink"));
        assertThat(ProjectChecks.status(admin, invitation.accountId())).isEqualTo("INVITED");
        assertThat(single(admin, invitation.email()).path("cancelled").asBoolean(true)).isFalse();
        assertError(accept(Role.AGENT, invitation.token()), 400, "INVITATION_INVALID");
        accept(Role.AGENT, newToken).expectStatus(204);
        assertThat(ProjectChecks.status(admin, invitation.accountId())).isEqualTo("ACTIVE");
        PeopleSteps.signInAttempt(console(), invitation.email(), Fixtures.PASSWORD).expectStatus(204);
    }

    @ParameterizedTest(name = "{0} for an account that is {1}")
    @CsvSource({"resend, ACTIVE", "cancel, ACTIVE", "resend, SUSPENDED", "cancel, SUSPENDED"})
    @DisplayName("S1A-AC-41 sending again or cancelling for an account that accepted before is 400 "
            + "INVITATION_ALREADY_ACCEPTED and changes nothing")
    void notAfterAcceptance(String action, String status) {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        if (status.equals("SUSPENDED")) {
            PeopleSteps.suspend(admin, agent.accountId());
        }

        ApiResponse response = admin.client().post(invitation(agent.accountId(), action)).send();

        assertError(response, 400, "INVITATION_ALREADY_ACCEPTED");
        assertThat(ProjectChecks.status(admin, agent.accountId())).isEqualTo(status);
        assertThat(pending(admin, agent.email())).as("pending entries of an account that accepted").isEmpty();
        if (status.equals("ACTIVE")) {
            PeopleSteps.signInAttempt(console(), agent.email(), agent.password()).expectStatus(204);
        }
    }

    @Test
    @DisplayName("S1A-AC-42 the pending list keeps a cancelled invitation, marked cancelled, next to one that is not")
    void cancelledStaysInThePendingList() {
        Session admin = fixtures.signInAdmin();
        Invitation cancelled = fixtures.invite(admin, Role.AGENT);
        Invitation open = fixtures.invite(admin, Role.AGENT);

        PeopleSteps.cancel(admin, cancelled.accountId());

        JsonNode cancelledEntry = single(admin, cancelled.email());
        JsonNode openEntry = single(admin, open.email());
        assertThat(cancelledEntry.path("accountId").asLong(-1)).isEqualTo(cancelled.accountId());
        assertThat(cancelledEntry.path("cancelled").isBoolean()).isTrue();
        assertThat(cancelledEntry.path("cancelled").asBoolean(false)).isTrue();
        assertThat(cancelledEntry.path("name").asString(null)).isEqualTo(cancelled.name());
        assertThat(cancelledEntry.path("role").asString(null)).isEqualTo("AGENT");
        assertThat(openEntry.path("cancelled").isBoolean()).isTrue();
        assertThat(openEntry.path("cancelled").asBoolean(true)).isFalse();
    }
}
