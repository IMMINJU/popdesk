package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mail of an issue filed for a customer whose language is Korean, and what happens to that mail
 * when the customer changes while it waits in the send queue. The mail is kept in the queue by
 * closing the {@link SmtpGate}: the first attempt fails, the row stays, and the change is made before
 * the retry 1 second later on the app's clock ({@code boundary.QueuedMailBoundaryTest}).
 */
class KoreanOnBehalfMailTest extends GatedMailTest {

    private NotifySteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
    }

    private void retry() {
        gate().pass();
        clock.advance(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("S3-AC-37 the mail to a Korean customer is in Korean: the snapshot text, a portal link, 지원팀, and nothing of the agent or the content")
    void mailToTheCustomerInKorean() {
        Scene s = steps.scene();
        String word = IssueFlow.word();

        Issue issue = OnBehalfMails.file(flow, s, s.customer(), "The invoice " + word + " is wrong.");

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        assertThat(m.to()).containsExactly(s.customer().email());
        OnBehalfMails.assertLinksAndHidesStaff(m, s, issue.key(), word);
        assertThat(hasHangul(m.subject())).as("Korean subject: " + m.subject()).isTrue();
        assertThat(m.text()).contains("지원팀");
        WebhookSnapshots.assertMatches("ko-filed-on-behalf.txt", OnBehalfMails.snapshotForm(m, issue.key()));
    }

    @Test
    @DisplayName("S3-AC-37 a queued mail still goes to the customer taken out of the company, who still sees the issue filed for them")
    void queuedMailReachesCustomerTakenOutOfTheCompany() {
        Scene s = steps.scene();
        gate().hold();
        Issue issue = OnBehalfMails.file(flow, s, s.customer(), "Taken down on the phone.");
        gate().awaitHeldTo(s.customer().email());

        flow.removeCustomer(s.admin(), s.code(), s.customer().accountId());
        s.customer().client().get("/api/portal/issues/" + issue.key()).expect(200);
        retry();

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        assertThat(m.text()).as("mail to the customer who left").contains(NotifySteps.portalIssueLink(issue.key()));
    }

    @Test
    @DisplayName("S3-AC-37 a queued mail is not sent to a customer suspended before it goes out, while another customer's queued mail is")
    void queuedMailSkipsSuspendedCustomer() {
        Scene s = steps.scene();
        Session second = steps.person(s.admin(), Role.CUSTOMER, "Second", s.code());
        gate().hold();
        OnBehalfMails.file(flow, s, s.customer(), "For the first customer.");
        Issue ofSecond = OnBehalfMails.file(flow, s, second, "For the second customer.");
        gate().awaitHeldTo(s.customer().email());
        gate().awaitHeldTo(second.email());

        flow.suspend(s.admin(), s.customer().accountId());
        retry();

        ReceivedMail sent = awaitNotification(mail(), second.email());
        assertThat(sent.text()).as("mail to the customer who stays").contains(ofSecond.key());
        Wait.holds("no mail to the suspended customer", Duration.ofSeconds(3),
                () -> notifications(mail(), s.customer().email()).isEmpty());
        assertThat(gate().held()).as("attempts held while the gate was closed")
                .filteredOn(h -> h.isTo(s.customer().email())).hasSize(1);
    }
}
