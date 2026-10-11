package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.account;
import static io.popdesk.project.PeopleSteps.invitation;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertNoStore;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.PasswordSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mail side of suspending, resetting a password, and sending an invitation again, with SMTP on. */
class PeopleMailTest extends ConnectedAppTest {

    private static final String RESET_LINK = "/reset-password#token=";
    private static final String INVITE_LINK = "/invite#token=";

    private List<ReceivedMail> mailsWith(String email, String text) {
        return PasswordSteps.mailsWith(mail(), email, text);
    }

    private List<ReceivedMail> awaitMailsWith(String email, String text, int n) {
        return PasswordSteps.awaitMailsWith(mail(), email, text, n);
    }

    @Test
    @DisplayName("S1A-AC-34 a suspended customer gets no mail about a new comment, while a colleague does")
    void suspendedAccountGetsNoMail() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session suspended = fixtures.customer(admin, company.code());
        Session colleague = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue ofSuspended = fixtures.fileIssue(suspended, company.code());
        Issue ofColleague = fixtures.fileIssue(colleague, company.code());

        PeopleSteps.suspend(admin, suspended.accountId());
        fixtures.comment(agent, ofSuspended.key(), "An answer to the suspended customer");
        fixtures.comment(agent, ofColleague.key(), "An answer to the colleague");

        awaitMailsWith(colleague.email(), ofColleague.key(), 1);
        Wait.holds("no comment mail to the suspended customer", Duration.ofSeconds(3),
                () -> mailsWith(suspended.email(), ofSuspended.key()).isEmpty());
    }

    @Test
    @DisplayName("S1A-AC-36 resetting a password queues the reset mail to that account")
    void resetSendsTheResetMail() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        Session bystander = fixtures.agent(admin);
        assertThat(mailsWith(agent.email(), RESET_LINK)).as("reset mails before the reset").isEmpty();

        PeopleSteps.resetPassword(admin, agent.accountId());

        awaitMailsWith(agent.email(), RESET_LINK, 1);
        Wait.holds("one reset mail, and none to another account", Duration.ofSeconds(2),
                () -> mailsWith(agent.email(), RESET_LINK).size() == 1
                        && mailsWith(bystander.email(), RESET_LINK).isEmpty());
    }

    @Test
    @DisplayName("S1A-AC-37 with SMTP the reset response has resetLink null, with Cache-Control no-store")
    void noResetLinkInResponseWithSmtp() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);

        ApiResponse reset = admin.client().post(account(agent.accountId()) + "/password-reset").expect(200);

        assertNoStore(reset);
        assertThat(reset.json().has("resetLink")).as("resetLink field in %s", reset.snippet()).isTrue();
        assertThat(reset.json().path("resetLink").isNull()).as("resetLink is null in %s", reset.snippet()).isTrue();
        assertThat(reset.text()).doesNotContain("token=");
    }

    @Test
    @DisplayName("S1A-AC-38 with SMTP sending again queues a new invitation mail with a new link, the response has "
            + "no link, and the old link stops working")
    void resendSendsANewInvitationMail() {
        Session admin = fixtures.signInAdmin();
        Invitation invited = fixtures.invite(admin, Role.AGENT);

        ApiResponse resent = admin.client().post(invitation(invited.accountId(), "resend")).expect(200);

        assertThat(resent.jsonLong("accountId")).isEqualTo(invited.accountId());
        assertThat(resent.json().path("inviteLink").isNull()).as("inviteLink is null in %s", resent.snippet())
                .isTrue();
        List<ReceivedMail> mails = awaitMailsWith(invited.email(), INVITE_LINK, 2);
        List<String> tokens = mails.stream().map(m -> Fixtures.tokenOf(Fixtures.linkIn(m.text()))).toList();
        assertThat(tokens).as("tokens of the invitation mails").hasSize(2).doesNotHaveDuplicates()
                .contains(invited.token());
        String newToken = tokens.stream().filter(t -> !t.equals(invited.token())).findFirst().orElseThrow();
        assertError(console().post("/api/public/invitations/accept")
                .json(Map.of("token", invited.token(), "password", Fixtures.PASSWORD)).send(), 400,
                "INVITATION_INVALID");
        console().post("/api/public/invitations/accept")
                .json(Map.of("token", newToken, "password", Fixtures.PASSWORD)).expect(204);
    }
}
