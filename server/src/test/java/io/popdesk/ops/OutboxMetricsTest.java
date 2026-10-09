package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.Prometheus.Names;
import io.popdesk.ops.Prometheus.Sample;
import io.popdesk.ops.Prometheus.Scrape;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.Wait;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Outbox metrics with both channels on (architecture section 4 "outbox", OPS-2, TM-30). */
class OutboxMetricsTest extends ConnectedAppTest {

    @Autowired
    Environment environment;

    private Prometheus prometheus;

    @BeforeEach
    void setUp() {
        prometheus = new Prometheus(environment);
    }

    private static double value(Scrape scrape, String name, String channel) {
        return scrape.forChannel(name, channel).map(Sample::value)
                .orElseThrow(() -> new AssertionError("no " + name + "{channel=" + channel + "} in the scrape"));
    }

    @Test
    @DisplayName("S0-AC-78 queued, failed, and sent are measured for each channel, mail and webhook")
    void gaugesPerChannel() {
        fixtures.signInAdmin();

        Scrape scrape = prometheus.scrape();

        for (String name : List.of(Names.OUTBOX_QUEUED, Names.OUTBOX_FAILED, Names.OUTBOX_SENT)) {
            for (String channel : List.of("mail", "webhook")) {
                assertThat(scrape.forChannel(name, channel)).as("%s{channel=%s}", name, channel).isPresent();
            }
        }
    }

    @Test
    @DisplayName("S0-AC-78 a delivered invitation mail adds one to the mail channel's sent count")
    void sentMailCounted() {
        Session admin = fixtures.signInAdmin();
        double before = value(prometheus.scrape(), Names.OUTBOX_SENT, "mail");

        fixtures.invite(admin, Role.AGENT);

        Wait.untilAsserted("the sent count", () -> assertThat(value(prometheus.scrape(), Names.OUTBOX_SENT, "mail"))
                .isGreaterThanOrEqualTo(before + 1));
    }

    @Test
    @DisplayName("S0-AC-78 a webhook row that keeps failing shows in the webhook channel's queued gauge")
    void queuedWebhookCounted() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        webhook().respondWith(500);

        Issue issue = fixtures.fileIssue(customer, company.code());
        webhook().await("a first attempt for " + issue.key(), r -> r.text().contains(issue.key()));

        Wait.untilAsserted("the queued gauge", () -> assertThat(value(prometheus.scrape(), Names.OUTBOX_QUEUED,
                "webhook")).isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("S0-AC-78 the metrics hold neither the webhook URL nor the SMTP password")
    void noSecretsInMetrics() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        webhook().await("the new issue " + issue.key(), r -> r.text().contains(issue.key()));

        String text = prometheus.scrape().text();

        assertThat(LogForms.foundIn(text, List.of(webhook().url(), "127.0.0.1:" + webhook().port(),
                MailServer.PASSWORD))).as("secrets in the metrics").isEmpty();
    }
}
