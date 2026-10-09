package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.Wait;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The system status with SMTP and the webhook on (OPS-3 in part, TM-49). */
class SystemStatusWithSmtpTest extends ConnectedAppTest {

    @Test
    @DisplayName("S0-AC-81 with SMTP and webhook both channels are on, and the body has no SMTP password or webhook URL")
    void channelsOnWithoutSecrets() {
        Session admin = fixtures.signInAdmin();

        String status = admin.client().get(SystemStatusTest.STATUS).expect(200).text();
        String failed = admin.client().get(SystemStatusTest.FAILED).expect(200).text();

        JsonNode json = admin.client().get(SystemStatusTest.STATUS).expect(200).json();
        assertThat(json.path("mail").path("enabled").asBoolean(false)).as("mail.enabled").isTrue();
        assertThat(json.path("webhook").path("enabled").asBoolean(false)).as("webhook.enabled").isTrue();
        for (String body : List.of(status, failed)) {
            assertThat(LogForms.foundIn(body, List.of(MailServer.PASSWORD, webhook().url(),
                    "127.0.0.1:" + webhook().port(), "/hook"))).as("secrets in %s", body).isEmpty();
        }
    }

    @Test
    @DisplayName("S0-AC-81 after a mail is delivered, mail.lastSuccessAt is the time it was sent")
    void lastSuccessAfterMail() {
        Session admin = fixtures.signInAdmin();
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);

        fixtures.invite(admin, Role.AGENT);

        Wait.untilAsserted("mail.lastSuccessAt", () -> {
            JsonNode mail = admin.client().get(SystemStatusTest.STATUS).expect(200).json().path("mail");
            assertThat(OpsChecks.instant(mail.path("lastSuccessAt"), "mail.lastSuccessAt")).isEqualTo(at);
        });
    }

    @Test
    @DisplayName("S0-AC-81 a webhook that keeps failing is counted as queued for the webhook channel")
    void queuedWhileFailing() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        webhook().respondWith(500);

        Issue issue = fixtures.fileIssue(customer, company.code());
        webhook().await("a first attempt for " + issue.key(), r -> r.text().contains(issue.key()));

        Wait.untilAsserted("webhook.queued", () -> assertThat(admin.client().get(SystemStatusTest.STATUS)
                .expect(200).json().path("webhook").path("queued").asLong(-1)).isGreaterThanOrEqualTo(1));
    }
}
