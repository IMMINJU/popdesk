package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.everything;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mail a customer gets when staff answer in public (SMTP on, GreenMail, installation language en). */
class CommentMailTest extends ConnectedAppTest {

    private NotifySteps steps;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
    }

    /** Mail anywhere in GreenMail whose text or subject holds the issue key, one entry per stored copy. */
    private List<ReceivedMail> mailAbout(String key) {
        return mail().messages().stream()
                .filter(m -> !m.text().contains("/invite#token="))
                .filter(m -> everything(m).contains(key))
                .toList();
    }

    @Test
    @DisplayName("S0-AC-70 an agent's public comment mails the customer who filed the issue a portal link, and nothing of the agent")
    void agentAnswerMailsCustomer() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        fixtures.comment(s.agent(), issue.key(), "Answer " + Unique.number());

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        assertThat(m.to()).containsExactly(s.customer().email());
        assertThat(m.text()).contains(NotifySteps.portalIssueLink(issue.key())).doesNotContain(CONSOLE_HOST);
        assertThat(everything(m)).doesNotContain(s.agent().name()).doesNotContainIgnoringCase(s.agent().email());
        assertThat(hasHangul(m.subject() + m.text())).as("Korean in an en mail: " + m.subject() + "\n" + m.text())
                .isFalse();
    }

    @Test
    @DisplayName("S0-AC-70 an admin's public comment mails the customer without the admin's name or email")
    void adminAnswerHidesAdmin() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Session admin = steps.person(s.admin(), Role.ADMIN, "Adminname", null);

        fixtures.comment(admin, issue.key(), "Admin answer " + Unique.number());

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        assertThat(everything(m)).doesNotContain(admin.name()).doesNotContainIgnoringCase(admin.email());
        assertThat(m.text()).contains(NotifySteps.portalIssueLink(issue.key()));
    }

    @Test
    @DisplayName("S0-AC-70 the customer's own comment and an internal comment mail nobody; the public answer after them mails the customer once")
    void ownAndInternalCommentsMailNobody() {
        Scene s = steps.scene();
        Session colleague = steps.person(s.admin(), Role.CUSTOMER, "Colleague", s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        fixtures.comment(s.customer(), issue.key(), "My own follow-up " + Unique.number());
        fixtures.internalComment(s.agent(), issue.key(), "Internal note " + Unique.number());
        Wait.holds("no mail about " + issue.key() + " after the customer's own and an internal comment",
                Duration.ofSeconds(3), () -> mailAbout(issue.key()).isEmpty()
                        && notifications(mail(), s.customer().email()).isEmpty());

        fixtures.comment(s.agent(), issue.key(), "Public answer " + Unique.number());
        awaitNotification(mail(), s.customer().email());
        Wait.holds("one mail, to the customer who filed the issue", Duration.ofSeconds(3),
                () -> notifications(mail(), s.customer().email()).size() == 1);
        assertThat(notifications(mail(), colleague.email())).as("mail to a colleague in the same company").isEmpty();
        assertThat(notifications(mail(), s.agent().email())).as("mail to the agent").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-71 each mail has one recipient: one copy, one To, no Cc or Bcc")
    void oneRecipient() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        fixtures.comment(s.agent(), issue.key(), "Answer " + Unique.number());

        awaitNotification(mail(), s.customer().email());
        Wait.holds("one stored copy of the mail about " + issue.key(), Duration.ofSeconds(3),
                () -> mailAbout(issue.key()).size() == 1);
        ReceivedMail m = mailAbout(issue.key()).getFirst();
        assertThat(m.to()).containsExactly(s.customer().email());
        assertThat(m.cc()).isEmpty();
        assertThat(m.header("Bcc")).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-71 a line break in the issue title does not break the subject or add a recipient")
    void lineBreakInTitleStaysInSubject() {
        Scene s = steps.scene();
        long n = Unique.number();
        String victim = "victim-" + n + "@example.test";
        Issue issue = fixtures.fileIssue(s.customer(),
                NewIssue.in(s.code()).title("Printer " + n + "\nBcc: " + victim));

        fixtures.comment(s.agent(), issue.key(), "Answer " + n);

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        assertThat(m.subject()).as("subject").doesNotContain("\n").doesNotContain("\r");
        if (m.subject().contains("Printer " + n)) {
            assertThat(m.subject()).as("the title in the subject, line break as a space")
                    .contains("Printer " + n + " Bcc: " + victim);
        }
        assertThat(m.to()).containsExactly(s.customer().email());
        assertThat(m.cc()).isEmpty();
        assertThat(m.header("Bcc")).isEmpty();
        Wait.holds("no mail to the address in the title", Duration.ofSeconds(3),
                () -> mail().messagesTo(victim).isEmpty());
    }
}
