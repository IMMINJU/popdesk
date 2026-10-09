package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** With SMTP and the webhook on: the SMTP password and the webhook URL never reach the log (PII-2, TM-30). */
class LogSecretsWithSmtpTest extends ConnectedAppTest {

    @Test
    @DisplayName("S0-AC-86 after sending mail and a webhook, no form of the SMTP password or webhook URL is in the log")
    void noSmtpPasswordOrWebhookUrl() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        webhook().await("the new issue " + issue.key(), r -> r.text().contains(issue.key()));

        String all = log.all();
        assertThat(LogForms.foundIn(all, List.of(MailServer.PASSWORD, webhook().url(),
                "127.0.0.1:" + webhook().port()))).as("secrets in the log").isEmpty();
    }
}
