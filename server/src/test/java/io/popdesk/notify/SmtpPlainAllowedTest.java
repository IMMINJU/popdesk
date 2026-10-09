package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code SMTP_ALLOW_PLAINTEXT=true} with a server that has no STARTTLS (setup "connected", like Mailpit). */
class SmtpPlainAllowedTest extends ConnectedAppTest {

    @Test
    @DisplayName("S0-AC-74 with SMTP_ALLOW_PLAINTEXT=true the app sends over plain SMTP and warns about it at startup")
    void plainTextAllowedWithWarning() {
        Session admin = fixtures.signInAdmin();

        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        assertThat(mail().messagesTo(invitation.email())).as("mail over plain SMTP").hasSize(1);
        assertThat(log.lines()).as("startup warning about SMTP_ALLOW_PLAINTEXT")
                .anyMatch(l -> l.contains("WARN") && l.contains("SMTP_ALLOW_PLAINTEXT"));
        assertThat(log.all()).as("the log").doesNotContain(MailServer.PASSWORD);
    }
}
