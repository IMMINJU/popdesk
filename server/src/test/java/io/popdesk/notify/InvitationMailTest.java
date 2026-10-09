package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.hasHangul;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Invitation mail with SMTP on (GreenMail, installation language en). */
class InvitationMailTest extends ConnectedAppTest {

    @Test
    @DisplayName("S0-AC-72 an invitation mail links to the person's app: the portal for a customer, the console for an agent or admin")
    void linkGoesToThePersonsApp() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        Map<Role, Invitation> invitations = Map.of(
                Role.CUSTOMER, fixtures.invite(admin, Role.CUSTOMER, company.code()),
                Role.AGENT, fixtures.invite(admin, Role.AGENT),
                Role.ADMIN, fixtures.invite(admin, Role.ADMIN));

        invitations.forEach((role, invitation) -> {
            String host = role == Role.CUSTOMER ? PORTAL_HOST : CONSOLE_HOST;
            assertThat(invitation.link()).as("link for " + role).startsWith("http://" + host + "/invite#token=");
            ReceivedMail m = mail().awaitMessageTo(invitation.email());
            assertThat(m.to()).as("recipients for " + role).containsExactly(invitation.email());
            assertThat(m.cc()).isEmpty();
            assertThat(hasHangul(m.subject() + m.text())).as("Korean in an en mail: " + m.text()).isFalse();
        });
        Wait.holds("one mail per invitation", Duration.ofSeconds(3), () -> invitations.values().stream()
                .allMatch(i -> mail().messagesTo(i.email()).size() == 1));
    }

    @Test
    @DisplayName("S0-AC-72 the link in the invitation mail accepts the invitation on the person's host")
    void mailedLinkAccepts() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());

        String link = Fixtures.linkIn(mail().awaitMessageTo(invitation.email()).text());
        Session customer = fixtures.accept(invitation);

        assertThat(link).isEqualTo(invitation.link());
        assertThat(customer.client().get("/api/portal/me").expect(200).jsonText("email"))
                .isEqualToIgnoringCase(invitation.email());
    }
}
