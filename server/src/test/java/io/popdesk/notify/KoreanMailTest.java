package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.everything;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.ReceivedMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mail in the recipient's language. Accounts take {@code POPDESK_LOCALE} when invited, and this
 * context sets {@code ko}; {@link CommentMailTest} and {@link InvitationMailTest} see the default
 * {@code en}. The tests do not fix the wording, only that the text is Korean.
 */
class KoreanMailTest extends GatedMailTest {

    private NotifySteps steps;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-72 an invitation to a Korean account is written in Korean and links to the person's app")
    void invitationInKorean() {
        Scene s = steps.scene();
        Invitation customer = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        Invitation agent = fixtures.invite(s.admin(), Role.AGENT);

        ReceivedMail toCustomer = mail().awaitMessageTo(customer.email());
        ReceivedMail toAgent = mail().awaitMessageTo(agent.email());

        assertThat(hasHangul(toCustomer.subject())).as("Korean subject: " + toCustomer.subject()).isTrue();
        assertThat(hasHangul(toCustomer.text())).as("Korean text: " + toCustomer.text()).isTrue();
        assertThat(hasHangul(toAgent.text())).as("Korean text: " + toAgent.text()).isTrue();
        assertThat(Fixtures.linkIn(toCustomer.text())).startsWith("http://" + PORTAL_HOST + "/invite#token=");
        assertThat(Fixtures.linkIn(toAgent.text())).startsWith("http://" + CONSOLE_HOST + "/invite#token=");
    }

    @Test
    @DisplayName("S0-AC-70 a staff answer mailed to a Korean customer is in Korean and names the writer 지원팀")
    void answerInKoreanFromSupportTeam() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        fixtures.comment(s.agent(), issue.key(), "Answer for the customer");

        ReceivedMail m = NotifySteps.awaitNotification(mail(), s.customer().email());
        assertThat(hasHangul(m.subject())).as("Korean subject: " + m.subject()).isTrue();
        assertThat(m.text()).contains("지원팀").contains(NotifySteps.portalIssueLink(issue.key()));
        assertThat(everything(m)).doesNotContain(s.agent().name()).doesNotContainIgnoringCase(s.agent().email());
    }
}
