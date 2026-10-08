package io.popdesk.issue;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Internal comments make no mail for the customer (SMTP on, GreenMail). */
class InternalCommentMailTest extends ConnectedAppTest {

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    /** Mail to the customer other than the invitation. */
    private List<ReceivedMail> notifications(Session customer) {
        return mail().messagesTo(customer.email()).stream()
                .filter(m -> !m.text().contains("/invite#token="))
                .toList();
    }

    @Test
    @DisplayName("S0-AC-53 an internal comment sends the customer no mail; the public answer after it sends one")
    void internalCommentSendsNoMail() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String secret = "Internal " + Unique.number();

        fixtures.internalComment(agent, issue.key(), secret);
        Wait.holds("no mail to the customer after an internal comment", Duration.ofSeconds(3),
                () -> notifications(s.customer()).isEmpty());
        fixtures.comment(agent, issue.key(), "Public answer");
        Wait.until("mail to the customer after the public answer",
                () -> notifications(s.customer()).isEmpty() ? null : notifications(s.customer()));
        Wait.holds("only the public answer's mail", Duration.ofSeconds(3),
                () -> notifications(s.customer()).size() == 1);

        assertThat(notifications(s.customer())).singleElement()
                .satisfies(m -> assertThat(m.raw()).doesNotContain(secret));
    }

    @Test
    @DisplayName("S0-AC-53 an internal comment by an admin sends the customer no mail either")
    void internalCommentByAdminSendsNoMail() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        fixtures.internalComment(s.admin(), issue.key(), "Admin note " + Unique.number());

        Wait.holds("no mail to the customer after an admin's internal comment", Duration.ofSeconds(3),
                () -> notifications(s.customer()).isEmpty());
        assertThat(s.customer().client().get("/api/portal/issues/" + issue.key() + "/comments").expect(200)
                .json().path("totalElements").asLong()).isZero();
    }
}
