package io.popdesk.notify;

import static io.popdesk.notify.Delivery.SANDBOX_TO;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A sandbox with an address, apart from its mail: no webhook although {@code NOTIFY_WEBHOOK_URL} is
 * set, the warning at startup, and what the system status says.
 *
 * <p>A webhook row that was in the queue before the sandbox started (a copied database) cannot be made
 * here: this app never queues one, and the tests write no rows. That part of S4B-AC-04 is left to a
 * test of the sender itself and to the hidden end-to-end test.
 */
class SandboxChannelsTest extends Delivery.SandboxWithAddress {

    @Test
    @DisplayName("S4B-AC-04 in a sandbox a new issue, a customer's comment, and a resolved issue post no "
            + "webhook and queue no webhook row, while the mail about it comes to the sandbox address")
    void noWebhook() {
        Cast c = cast();
        IssueFlow flow = new IssueFlow(fixtures, clock);
        String key = fixtures.fileIssue(c.customer(), c.code()).key();
        fixtures.comment(c.customer(), key, "Question " + Unique.number());
        flow.changeStatus(c.agent(), key, "IN_PROGRESS");
        flow.changeStatus(c.agent(), key, "RESOLVED");

        Delivery.awaitRedirected(mail(), c.customer().accountId(), m -> m.text().contains(key));

        Wait.holds("no webhook request about " + key + " and no webhook row in the send queue",
                Duration.ofSeconds(3), () -> Delivery.webhooks(webhook(), key).isEmpty()
                        && db.count("select count(*) from outbox where channel = 'WEBHOOK'") == 0);
        assertThat(Delivery.auditCopies(webhook(), c.code())).as("outside copies of audit entries about %s",
                c.code()).isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-07, S4B-AC-38 a sandbox warns at startup what is off and where mail goes, the "
            + "address masked after its first character, and the log never holds the address")
    void startupWarning() {
        Cast c = cast();
        String key = fixtures.fileIssue(c.customer(), c.code()).key();
        fixtures.comment(c.agent(), key, "Answer " + Unique.number());
        Delivery.awaitRedirected(mail(), c.customer().accountId(), m -> m.text().contains(key));

        List<String> warnings = log.lines().stream()
                .filter(l -> l.contains("WARN") && l.toLowerCase(Locale.ROOT).contains("sandbox")).toList();

        assertThat(Delivery.maskedLines(warnings, SANDBOX_TO.charAt(0)))
                .as("WARN lines about the sandbox that hold the masked address, among %s", warnings)
                .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("webhook"));
        assertThat(Delivery.foundIn(log.all(), SANDBOX_TO)).as("the sandbox address in the whole log").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-08 the status of a sandbox with an address: sandbox and address set, mail on, webhook "
            + "and the outside copy off although both are set up, and no address in the body")
    void status() {
        Session admin = admin();

        String body = admin.client().get(Delivery.STATUS).expect(200).text();
        JsonNode status = Delivery.status(admin);

        assertThat(status.path("sandbox").isBoolean() && status.path("sandbox").asBoolean()).as("sandbox in %s",
                status).isTrue();
        assertThat(status.path("sandboxMailToSet").isBoolean() && status.path("sandboxMailToSet").asBoolean())
                .as("sandboxMailToSet in %s", status).isTrue();
        assertThat(status.path("mail").path("enabled").asBoolean(false)).as("mail.enabled").isTrue();
        assertThat(status.path("webhook").path("enabled").asBoolean(true)).as("webhook.enabled").isFalse();
        assertThat(status.path("auditForward").path("enabled").asBoolean(true)).as("auditForward.enabled").isFalse();
        assertThat(Delivery.foundIn(body, SANDBOX_TO)).as("the sandbox address in the status").isEmpty();
    }
}
