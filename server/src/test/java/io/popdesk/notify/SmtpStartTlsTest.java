package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.StartTlsAppTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SMTP on a port other than 465 with a server that offers STARTTLS (setup "starttls", the production default). */
class SmtpStartTlsTest extends StartTlsAppTest {

    @Test
    @DisplayName("S0-AC-74 on a port other than 465 the app switches to TLS with STARTTLS before it sends")
    void sendsAfterStartTls() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        int upgradedBefore = mail().startTlsConnections();
        int refusedBefore = mail().plainTextRefusals();

        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());

        assertThat(mail().messagesTo(invitation.email())).hasSize(1);
        assertThat(mail().startTlsConnections()).as("connections switched to TLS").isGreaterThan(upgradedBefore);
        assertThat(mail().plainTextRefusals()).as("commands sent before switching to TLS").isEqualTo(refusedBefore);
    }

    @Test
    @DisplayName("S0-AC-74 without SMTP_ALLOW_PLAINTEXT the app gives no plain text warning at startup and sends over TLS")
    void noPlainTextWarning() {
        Session admin = fixtures.signInAdmin();
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        assertThat(mail().messagesTo(invitation.email())).hasSize(1);
        assertThat(log.lines()).as("startup warnings about SMTP_ALLOW_PLAINTEXT")
                .noneMatch(l -> l.contains("WARN") && l.contains("SMTP_ALLOW_PLAINTEXT"));
    }
}
