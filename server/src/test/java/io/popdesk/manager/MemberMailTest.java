package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.acceptOnPortal;
import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.assertSameResponse;
import static io.popdesk.manager.ManagerSteps.customer;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.invite;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.myCompanies;
import static io.popdesk.manager.ManagerSteps.pages;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.resend;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rowId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Existing;
import io.popdesk.manager.ManagerSteps.ExistingKind;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * A manager's invitations with SMTP: a new email gets the invitation mail, the email of an existing
 * account gets nothing and becomes a membership request, and sending again replaces the link.
 *
 * <p>"No mail" is checked after a later mail arrived: the test causes one more mail, waits for it,
 * and then counts the mail to the address that must get none.
 */
class MemberMailTest extends ConnectedAppTest {

    private record Scene(Session admin, Company company, Company other, Session manager) {

        String code() {
            return company.code();
        }
    }

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        return new Scene(admin, company, other, ManagerSteps.manager(fixtures, admin, company.code()));
    }

    /** The mail text with the invitation link taken out. */
    private static String withoutLink(ReceivedMail mail) {
        return mail.text().replace(Fixtures.linkIn(mail.text()), "LINK");
    }

    /** Has the manager invite a new email and waits for that mail: every mail queued before is out by then. */
    private void awaitALaterMail(Scene s) {
        String marker = Unique.email("marker");
        invited(s.manager(), s.code(), marker, "Marker");
        mail().awaitMessageTo(marker);
    }

    // S2-AC-13

    @Test
    @DisplayName("S2-AC-13 inviting a new email makes an INVITED customer of that company only and sends one "
            + "invitation mail like the admin's; the link signs the person in to the portal")
    void newEmailIsInvitedByMail() {
        Scene s = scene();
        String name = Unique.text("Invited");
        Invitation byAdmin = fixtures.invite(s.admin(), Role.CUSTOMER, Unique.email("by-admin"), name,
                List.of(s.code()));
        String email = Unique.email("by-manager");

        invite(s.manager(), s.code(), email, name).expectStatus(204);

        ReceivedMail mail = mail().awaitMessageTo(email);
        ReceivedMail adminMail = mail().awaitMessageTo(byAdmin.email());
        String link = Fixtures.linkIn(mail.text());
        assertThat(link).startsWith("http://" + PORTAL_HOST + "/invite#token=");
        assertThat(mail.subject()).isEqualTo(adminMail.subject());
        assertThat(mail.from()).isEqualTo(adminMail.from());
        assertThat(withoutLink(mail)).isEqualTo(withoutLink(adminMail));
        Wait.holds("only one invitation mail to " + email, Duration.ofSeconds(3),
                () -> mail().messagesTo(email).size() == 1);

        List<JsonNode> accounts = pages(s.admin().client(), "/api/console/accounts", "role", "CUSTOMER", "status",
                "INVITED").stream().filter(a -> email.equals(a.path("email").asString(null))).toList();
        assertThat(accounts).as("INVITED customer accounts with " + email).singleElement()
                .satisfies(a -> assertThat(a.path("name").asString(null)).isEqualTo(name));
        long accountId = accounts.getFirst().path("id").asLong(-1);
        assertThat(pendingInvitations(s.admin(), email)).singleElement()
                .satisfies(p -> assertThat(p.path("companies")).extracting(c -> c.path("code").asString(null))
                        .containsExactly(s.code()));
        assertThat(customer(s.admin(), s.code(), accountId).path("status").asString(null)).isEqualTo("INVITED");
        assertThat(row(s.manager(), s.code(), email).path("status").asString(null)).isEqualTo("INVITED");

        ApiClient browser = portal();
        acceptOnPortal(browser, Fixtures.tokenOf(link)).expectStatus(204);

        ApiResponse me = browser.get("/api/portal/me").expect(200);
        assertThat(me.jsonLong("id")).isEqualTo(accountId);
        assertThat(me.jsonText("email")).isEqualTo(email);
        assertThat(me.json().path("companies")).extracting(c -> c.path("code").asString(null))
                .containsExactly(s.code());
        assertThat(row(s.manager(), s.code(), email).path("status").asString(null)).isEqualTo("ACTIVE");
    }

    // S2-AC-14

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExistingKind.class)
    @DisplayName("S2-AC-14 inviting the email of an existing account, whatever its role and status, leaves the "
            + "account as it was, sends it no mail, and leaves one membership request with the name and email "
            + "the manager typed")
    void existingEmailBecomesARequest(ExistingKind kind) {
        Scene s = scene();
        Existing existing = kind.make(fixtures, s.admin(), s.other().code());
        String typedName = Unique.text("Typed");
        Map<String, Object> accountBefore = db.row("select * from account where id = ?", existing.accountId());
        List<Long> otherBefore = customerIds(s.admin(), s.other().code());
        int mailBefore = mail().messagesTo(existing.email()).size();

        invite(s.manager(), s.code(), existing.email(), typedName).expectStatus(204);

        assertThat(requests(s.admin(), s.code())).singleElement().satisfies(r -> {
            assertThat(r.path("name").asString(null)).isEqualTo(typedName);
            assertThat(r.path("email").asString(null)).isEqualTo(existing.email());
            assertThat(r.path("company").path("code").asString(null)).isEqualTo(s.code());
            assertThat(r.path("requestedBy").path("accountId").asLong(-1)).isEqualTo(s.manager().accountId());
            assertThat(r.path("account").path("accountId").asLong(-1)).isEqualTo(existing.accountId());
            assertThat(r.path("account").path("name").asString(null)).isEqualTo(existing.name());
        });
        assertThat(db.row("select * from account where id = ?", existing.accountId())).isEqualTo(accountBefore);
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(existing.accountId());
        assertThat(customerIds(s.admin(), s.other().code())).isEqualTo(otherBefore);
        awaitALaterMail(s);
        assertThat(mail().messagesTo(existing.email())).as("mail to " + existing.email()).hasSize(mailBefore);
    }

    // S2-AC-29

    @Test
    @DisplayName("S2-AC-29 sending again for a member who has not accepted and belongs to this company only "
            + "sends one more invitation mail with a new link and ends the old link")
    void sendingAgainReplacesTheLink() {
        Scene s = scene();
        Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        String rowId = rowId(s.manager(), s.code(), invitation.email());

        ApiResponse response = s.manager().client().post(resend(s.code(), rowId)).expect(204);

        assertThat(response.body()).as("body of the 204").isEmpty();
        List<ReceivedMail> mails = Wait.until("a second invitation mail to " + invitation.email(), () -> {
            List<ReceivedMail> to = mail().messagesTo(invitation.email());
            return to.size() >= 2 ? to : null;
        });
        assertThat(mails).hasSize(2);
        String newToken = Fixtures.tokenOf(Fixtures.linkIn(mails.getLast().text()));
        assertThat(newToken).isNotEqualTo(invitation.token());
        assertThat(rowId(s.manager(), s.code(), invitation.email())).isEqualTo(rowId);
        assertError(acceptOnPortal(portal(), invitation.token()), 400, "INVITATION_INVALID");
        acceptOnPortal(portal(), newToken).expectStatus(204);
        assertThat(row(s.manager(), s.code(), invitation.email()).path("status").asString(null)).isEqualTo("ACTIVE");
    }

    // S2-AC-30

    @Test
    @DisplayName("S2-AC-30 sending again on the row of a pending membership request answers as for a member who "
            + "has not accepted and does nothing: no mail, the account and the request as they were")
    void sendingAgainOnARequestRowDoesNothing() {
        Scene s = scene();
        Existing existing = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        Invitation pending = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        invited(s.manager(), s.code(), existing.email(), "Requested");
        String requestRow = rowId(s.manager(), s.code(), existing.email());
        String pendingRow = rowId(s.manager(), s.code(), pending.email());
        List<JsonNode> requestsBefore = requests(s.admin(), s.code());
        Map<String, Object> accountBefore = db.row("select * from account where id = ?", existing.accountId());
        int mailBefore = mail().messagesTo(existing.email()).size();

        ApiResponse onRequest = s.manager().client().post(resend(s.code(), requestRow)).send();
        ApiResponse onPending = s.manager().client().post(resend(s.code(), pendingRow)).send();

        onPending.expectStatus(204);
        assertSameResponse(onPending, onRequest);
        assertThat(requests(s.admin(), s.code())).isEqualTo(requestsBefore);
        assertThat(db.row("select * from account where id = ?", existing.accountId())).isEqualTo(accountBefore);
        assertThat(rowId(s.manager(), s.code(), existing.email())).isEqualTo(requestRow);
        awaitALaterMail(s);
        assertThat(mail().messagesTo(existing.email())).as("mail to " + existing.email()).hasSize(mailBefore);
    }
}
