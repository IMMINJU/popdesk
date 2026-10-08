package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Links for email (conventions "Invitation links"): {@code POPDESK_PUBLIC_SCHEME}, the host of the
 * person's app, and {@code POPDESK_PUBLIC_PORT} unless it is the scheme's default. This setup has
 * {@code http} and {@code 80}. {@link HttpsPublicAddressTest} has {@code https} and {@code 443},
 * {@link UntrustedProxyTest} has {@code http} and {@code 8080}.
 */
class InvitationLinkTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-20 a customer's link uses the portal host and leaves out port 80 for http")
    void customerLinkUsesPortalHost() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, fixtures.createCompany(admin).code());

        assertThat(customer.link()).isEqualTo("http://" + AppTest.PORTAL_HOST + "/invite#token=" + customer.token());
    }

    @Test
    @DisplayName("S0-AC-20 agent and admin links use the console host and leave out port 80 for http")
    void staffLinksUseConsoleHost() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        Invitation newAdmin = fixtures.invite(admin, Role.ADMIN);

        assertThat(agent.link()).isEqualTo("http://" + AppTest.CONSOLE_HOST + "/invite#token=" + agent.token());
        assertThat(newAdmin.link()).isEqualTo("http://" + AppTest.CONSOLE_HOST + "/invite#token=" + newAdmin.token());
    }

    @Test
    @DisplayName("S0-AC-20 a copied invitation link has the same shape as the first one")
    void copiedLinkHasTheSameShape() {
        Session admin = AuthChecks.signedIn(console(), App.CONSOLE, AppTest.ADMIN_EMAIL, AppTest.ADMIN_PASSWORD);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, fixtures.createCompany(admin).code());

        String copied = admin.client().post("/api/console/invitations/" + customer.accountId() + "/link")
                .expect(200).jsonText("inviteLink");

        assertThat(copied).isEqualTo("http://" + AppTest.PORTAL_HOST + "/invite#token=" + Fixtures.tokenOf(copied));
        assertThat(Fixtures.tokenOf(copied)).isNotEqualTo(customer.token());
    }
}
