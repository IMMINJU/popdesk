package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mail and the webhook of an issue staff filed for a customer (SMTP on, installation language en). */
class OnBehalfMailTest extends ConnectedAppTest {

    private NotifySteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
    }

    @Test
    @DisplayName("S3-AC-37 filing for a customer mails that customer once, in English: the snapshot text, a portal link, the support team, and nothing of the agent or the content")
    void mailToTheCustomerInEnglish() {
        Scene s = steps.scene();
        String word = IssueFlow.word();

        Issue issue = OnBehalfMails.file(flow, s, s.customer(), "The invoice " + word + " is wrong.");

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        assertThat(m.to()).containsExactly(s.customer().email());
        assertThat(m.cc()).isEmpty();
        OnBehalfMails.assertLinksAndHidesStaff(m, s, issue.key(), word);
        assertThat(m.text()).containsIgnoringCase("support team");
        assertThat(hasHangul(m.subject() + m.text())).as("Korean in an en mail: " + m.subject() + "\n" + m.text())
                .isFalse();
        WebhookSnapshots.assertMatches("en-filed-on-behalf.txt", OnBehalfMails.snapshotForm(m, issue.key()));
        Wait.holds("one mail to the customer, none to the agent", Duration.ofSeconds(3),
                () -> notifications(mail(), s.customer().email()).size() == 1
                        && notifications(mail(), s.agent().email()).isEmpty());
    }

    @Test
    @DisplayName("S3-AC-41 a filing on behalf sent twice with one key queues one webhook and one mail")
    void filingSentAgainQueuesOneWebhookAndOneMail() {
        Scene s = steps.scene();
        OnBehalf filing = OnBehalf.of(s.code(), s.customer().accountId());
        String idempotencyKey = "key-" + UUID.randomUUID();

        ApiResponse first = flow.onBehalfRequest(s.agent(), filing).header("Idempotency-Key", idempotencyKey)
                .expect(201);
        ApiResponse second = flow.onBehalfRequest(s.agent(), filing).header("Idempotency-Key", idempotencyKey)
                .expect(201);

        String key = first.jsonText("key");
        assertThat(second.jsonText("key")).isEqualTo(key);
        awaitWebhooks(webhook(), key, 1);
        awaitNotification(mail(), s.customer().email());
        Wait.holds("one webhook and one mail about " + key, Duration.ofSeconds(3),
                () -> webhook().containing(key).size() == 1
                        && notifications(mail(), s.customer().email()).size() == 1);
    }
}
