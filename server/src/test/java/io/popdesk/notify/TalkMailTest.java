package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.everything;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.TalkMails.Caused;
import io.popdesk.notify.TalkMails.Mail;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The mails of slice 4 (SMTP on, GreenMail, installation language en): who is mailed when staff move
 * an issue, set an assignee, or mention someone, and when a membership request is made, and what the
 * six mails say in English.
 *
 * <p>"No mail" is read after a later mail arrived ({@link TalkFlow#awaitALaterMail}), not after a fixed
 * wait. Who gets a mail for which event, and that nobody else does, is {@code inbox.InboxRecipientsTest}.
 */
class TalkMailTest extends ConnectedAppTest {

    private NotifySteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
    }

    // S4-AC-52, S4-AC-53

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mail.class)
    @DisplayName("S4-AC-52 S4-AC-53 each new mail in English: the snapshot text, the link of the recipient's app, "
            + "[key] in the subject, and nothing of the staff who did it or of the issue's and the comment's content")
    void mailInEnglish(Mail mail) {
        Caused c = TalkMails.cause(mail, fixtures, flow, steps);

        ReceivedMail m = awaitNotification(mail(), c.recipient().email());

        TalkMails.assertLinksAndHides(m, c);
        if (c.toCustomer()) {
            assertThat(m.text()).as("text of a mail to a customer").containsIgnoringCase("support team");
        }
        assertThat(hasHangul(m.subject() + m.text())).as("Korean in an en mail: " + m.subject() + "\n" + m.text())
                .isFalse();
        WebhookSnapshots.assertMatches(mail.snapshot("en"), TalkMails.snapshotForm(m, c));
    }

    // S4-AC-49

    @Test
    @DisplayName("S4-AC-49 an agent who makes themself the assignee is not mailed; the agent an admin made the "
            + "assignee before them has the one mail of that")
    void agentWhoAssignsThemselfIsNotMailed() {
        Scene s = steps.scene();
        Session second = TalkMails.agentOf(fixtures, steps, s, "Second");
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        flow.assign(s.admin(), key, s.agent().accountId());
        awaitNotification(mail(), s.agent().email());
        flow.assign(second, key, second.accountId());

        TalkFlow.awaitALaterMail(fixtures, s.admin());
        assertThat(notifications(mail(), second.email())).as("mails to the agent who assigned themself").isEmpty();
        assertThat(notifications(mail(), s.agent().email())).as("mails to the agent an admin assigned").hasSize(1);
    }

    // S4-AC-50

    @Test
    @DisplayName("S4-AC-50 an admin mentioned in an internal comment is mailed once, with a console link")
    void mentionedAdminIsMailed() {
        Scene s = steps.scene();
        Session admin = steps.person(s.admin(), Role.ADMIN, "Adminname", null);
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        TalkFlow.mention(s.agent(), key, true, List.of(admin.accountId()));

        ReceivedMail m = awaitNotification(mail(), admin.email());
        assertThat(m.to()).containsExactly(admin.email());
        assertThat(m.text()).as("text of the mail").contains(NotifySteps.consoleIssueLink(key))
                .doesNotContain(PORTAL_HOST);
        TalkFlow.awaitALaterMail(fixtures, s.admin());
        assertThat(notifications(mail(), admin.email())).as("mails to the mentioned admin").hasSize(1);
    }

    @Test
    @DisplayName("S4-AC-50 a public comment that mentions the reporter mails them once: the mention mail, with a "
            + "portal link, and no answer mail next to it")
    void mentionedReporterGetsTheMentionMailOnly() {
        Scene s = steps.scene();
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        TalkFlow.mention(s.agent(), key, false, List.of(s.customer().accountId()));

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        TalkFlow.awaitALaterMail(fixtures, s.admin());
        assertThat(notifications(mail(), s.customer().email())).as("mails to the mentioned reporter").hasSize(1);
        assertThat(m.text()).as("text of the mail").doesNotContain(CONSOLE_HOST);
        assertThat(everything(m)).as("the whole mail").doesNotContainIgnoringCase(s.agent().name())
                .doesNotContainIgnoringCase(s.agent().email());
        WebhookSnapshots.assertMatches(Mail.MENTIONED.snapshot("en"),
                TalkMails.snapshotForm(m, key, NotifySteps.portalIssueLink(key), "{key}"));
    }

    // S4-AC-51

    @Test
    @DisplayName("S4-AC-51 the mail of a membership request names the company's code and links to that company "
            + "on the console, without the name and the email the manager typed")
    void membershipRequestMailNamesTheCompanyOnly() {
        Caused c = TalkMails.cause(Mail.MEMBERSHIP_REQUESTED, fixtures, flow, steps);

        ReceivedMail m = awaitNotification(mail(), c.recipient().email());

        assertThat(m.subject() + "\n" + m.text()).as("subject and text").contains(c.key());
        assertThat(m.text()).as("text of the mail").contains(TalkMails.consoleCompanyLink(c.key()));
        TalkMails.assertLinksAndHides(m, c);
    }

    @Test
    @DisplayName("S4-AC-51 an admin a membership request is about gets no mail for it; another admin does")
    void adminTheRequestIsAboutGetsNoMail() {
        ManagerSteps.Scene s = ManagerSteps.scene(fixtures);
        Session other = steps.person(s.admin(), Role.ADMIN, "Approver", null);
        Session target = steps.person(s.admin(), Role.ADMIN, "Wanted", null);

        ManagerSteps.invited(s.manager(), s.code(), target.email(), Unique.text("Typed"));

        ReceivedMail m = awaitNotification(mail(), other.email());
        assertThat(m.text()).as("text of the mail to another admin").contains(TalkMails.consoleCompanyLink(s.code()));
        ManagerSteps.awaitALaterMail(s, mail());
        assertThat(notifications(mail(), target.email())).as("mails to the admin the request is about").isEmpty();
        assertThat(notifications(mail(), other.email())).as("mails to another admin").hasSize(1);
    }
}
