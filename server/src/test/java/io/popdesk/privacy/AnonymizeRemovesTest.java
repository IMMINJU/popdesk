package io.popdesk.privacy;

import static io.popdesk.privacy.PrivacySteps.anonymize;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.auth.PasswordSteps;
import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.notify.Delivery.FakeSmtp;
import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What anonymizing removes with the account (S10A-AC-09, S10A-AC-10): sessions, the photo, the invitation and
 * reset tokens, the mail waiting in the send queue, the inbox, and the copies of the email and name in membership
 * requests.
 *
 * <p>The tokens are compared with a token nobody has: after anonymizing, a token that was good gets the same answer.
 * The inbox and the send queue of a person who can no longer sign in are read as rows ({@code notification},
 * {@code outbox} of V3), and the mail by what the mail server got.
 */
class AnonymizeRemovesTest extends PrivacyAppTest {

    private static ApiResponse accept(ApiClient browser, String token) {
        return browser.post("/api/public/invitations/accept").json(Map.of("token", token, "password",
                Fixtures.PASSWORD)).send();
    }

    /** Same status and same error code. */
    private static void assertSameRefusal(ApiResponse actual, ApiResponse ofAnUnknownToken, String what) {
        assertThat(actual.status()).as("status of %s: %s", what, actual.snippet()).isNotIn(200, 204)
                .isEqualTo(ofAnUnknownToken.status());
        assertThat(actual.errorCode()).as("code of %s", what).isEqualTo(ofAnUnknownToken.errorCode());
    }

    @Test
    @DisplayName("S10A-AC-09 after anonymizing, the old session cookie is 401, the photo is 404, and the person's "
            + "inbox items are gone")
    void sessionPhotoAndInbox() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        PhotoSteps.uploadPng(customer, PhotoSteps.png(64, 64, 7));
        Issue issue = fixtures.fileIssue(customer, company.code());
        fixtures.comment(agent, issue.key(), "A reply for " + issue.key());
        Wait.until("an inbox item of the customer about " + issue.key(),
                () -> TalkFlow.inboxOf(customer, issue.key()).isEmpty() ? null : Boolean.TRUE);
        assertThat(PhotoSteps.photo(agent, customer.accountId()).status()).as("the photo before").isEqualTo(200);
        assertThat(customer.client().get("/api/portal/me").send().status()).as("the session before").isEqualTo(200);

        anonymize(admin, customer.accountId());

        assertThat(customer.client().get("/api/portal/me").send().status()).as("GET /me with the old session")
                .isEqualTo(401);
        assertThat(PhotoSteps.photo(agent, customer.accountId()).status()).as("the photo after").isEqualTo(404);
        assertThat(db.count("select count(*) from notification where account_id = ?", customer.accountId()))
                .as("inbox rows of the anonymized customer").isZero();
    }

    @Test
    @DisplayName("S10A-AC-09 after anonymizing, an invitation link that was not used and a password reset link "
            + "answer like a token nobody has")
    void tokensStopWorking() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation invited = fixtures.invite(admin, Role.CUSTOMER, company.code());
        Session agent = fixtures.agent(admin, company.code());
        PeopleReset.reset(admin, agent.accountId());
        String resetToken = Fixtures.tokenOf(mail().awaitMessage("the reset mail to " + agent.email(),
                m -> m.isTo(agent.email()) && m.text().contains("#token=") && !m.text().contains("/invite#token="))
                .text());

        anonymize(admin, invited.accountId());
        anonymize(admin, agent.accountId());

        assertSameRefusal(accept(portal(), invited.token()), accept(portal(), "inv-" + Unique.text("unknown")),
                "accepting the old invitation");
        assertSameRefusal(PasswordSteps.confirm(console(), resetToken, "new-Password-" + Unique.number()),
                PasswordSteps.confirm(console(), "rst-" + Unique.text("unknown"), "new-Password-" + Unique.number()),
                "the old reset link");
    }

    @Test
    @DisplayName("S10A-AC-09 mail to the person that waits in the send queue when they are anonymized is never sent: "
            + "its row is gone, and after a later mail went out the old address got nothing more")
    void queuedMailIsDropped() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        int before = mail().messagesTo(customer.email()).size();
        holdMail();
        fixtures.comment(agent, issue.key(), "A reply that waits for " + issue.key());
        FakeSmtp.shared().awaitTakenTo(customer.email());
        assertThat(db.count("select count(*) from outbox where channel = 'MAIL' and recipient_account_id = ?",
                customer.accountId())).as("mail rows to the customer before").isPositive();

        anonymize(admin, customer.accountId());
        letMailGo();
        TalkFlow.awaitALaterMail(fixtures, admin);

        assertThat(db.count("select count(*) from outbox where channel = 'MAIL' and recipient_account_id = ?",
                customer.accountId())).as("mail rows to the anonymized customer").isZero();
        assertThat(mail().messagesTo(customer.email())).as("mail the old address got").hasSize(before);
        assertThat(mail().messagesTo(anonymousEmail(customer.accountId()))).as("mail to the anonymous address")
                .isEmpty();
    }

    @Test
    @DisplayName("S10A-AC-10 after anonymizing, a pending membership request for the person shows the anonymous "
            + "email and name in place of what the manager typed, and one the person asked for as a manager shows "
            + "them as the requester")
    void membershipRequests() {
        ManagerSteps.Scene scene = ManagerSteps.scene(fixtures, fixtures.signInAdmin());
        Session admin = scene.admin();
        Session wanted = fixtures.customer(admin, scene.other().code());
        Session joining = fixtures.customer(admin, scene.other().code());
        String typed = Unique.text("Typed name");
        ManagerSteps.invited(scene.manager(), scene.code(), wanted.email(), typed);
        ManagerSteps.invited(scene.manager(), scene.code(), joining.email(), Unique.text("Joining"));

        anonymize(admin, wanted.accountId());

        List<JsonNode> pending = ManagerSteps.requests(admin, scene.code());
        JsonNode ofWanted = only(pending, wanted.accountId());
        assertThat(ofWanted.path("email").asString(null)).as("email of %s", ofWanted)
                .isEqualTo(anonymousEmail(wanted.accountId()));
        assertThat(ofWanted.path("name").asString(null)).as("name of %s", ofWanted).isEqualTo(ANONYMOUS_NAME);
        assertThat(ofWanted.path("account").path("name").asString(null)).as("account.name of %s", ofWanted)
                .isEqualTo(ANONYMOUS_NAME);
        assertThat(ofWanted.toString()).as("the request for the anonymized customer").doesNotContain(wanted.email())
                .doesNotContain(typed).doesNotContain(wanted.name());
        JsonNode ofJoining = only(pending, joining.accountId());
        assertThat(ofJoining.path("email").asString(null)).as("email of the other request %s", ofJoining)
                .isEqualTo(joining.email());
    }

    private static JsonNode only(List<JsonNode> requests, long accountId) {
        List<JsonNode> found = requests.stream()
                .filter(r -> r.path("account").path("accountId").asLong(-1) == accountId).toList();
        assertThat(found).as("pending requests for account %d in %s", accountId, requests).hasSize(1);
        return found.getFirst();
    }

    /** The admin's password reset of an account; with SMTP on, the link goes to the person by mail. */
    private static final class PeopleReset {

        static void reset(Session admin, long accountId) {
            admin.client().post(PrivacySteps.ACCOUNTS + "/" + accountId + "/password-reset").expect(200);
        }
    }
}
