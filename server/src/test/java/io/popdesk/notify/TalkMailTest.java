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
import io.popdesk.support.Wait;
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
 * <p>"No mail" is read after a later mail arrived ({@link #awaitALaterMail}), not after a fixed wait.
 */
class TalkMailTest extends ConnectedAppTest {

    private NotifySteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
    }

    /** Has an invitation mailed and waits for it: every mail queued before is out by then. */
    private void awaitALaterMail(Session admin) {
        fixtures.invite(admin, Role.AGENT);
    }

    /** Waits for {@code n} notification mails to the person and returns them, oldest first. */
    private List<ReceivedMail> awaitNotifications(Session person, int n) {
        return Wait.until(n + " notification mail(s) to " + person.email(), () -> {
            List<ReceivedMail> found = notifications(mail(), person.email());
            return found.size() >= n ? found : null;
        });
    }

    private Session agentOf(Scene s, String prefix) {
        Session agent = steps.person(s.admin(), Role.AGENT, prefix, null);
        fixtures.assignAgent(s.admin(), s.code(), agent.accountId());
        return agent;
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

    // S4-AC-48

    @Test
    @DisplayName("S4-AC-48 staff resolving, reopening, and closing an issue mail the reporter once each, and "
            + "nobody else")
    void staffTransitionsMailTheReporter() {
        Scene s = steps.scene();
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        flow.changeStatus(s.agent(), key, "IN_PROGRESS");
        flow.changeStatus(s.agent(), key, "RESOLVED");
        ReceivedMail resolved = awaitNotifications(s.customer(), 1).get(0);
        flow.reopen(s.agent(), key);
        ReceivedMail reopened = awaitNotifications(s.customer(), 2).get(1);
        flow.changeStatus(s.agent(), key, "CLOSED");
        ReceivedMail closed = awaitNotifications(s.customer(), 3).get(2);

        String link = NotifySteps.portalIssueLink(key);
        WebhookSnapshots.assertMatches(Mail.ISSUE_RESOLVED.snapshot("en"),
                TalkMails.snapshotForm(resolved, key, link, "{key}"));
        WebhookSnapshots.assertMatches(Mail.ISSUE_REOPENED.snapshot("en"),
                TalkMails.snapshotForm(reopened, key, link, "{key}"));
        WebhookSnapshots.assertMatches(Mail.ISSUE_CLOSED.snapshot("en"),
                TalkMails.snapshotForm(closed, key, link, "{key}"));
        awaitALaterMail(s.admin());
        assertThat(notifications(mail(), s.customer().email())).as("mails to the reporter").hasSize(3);
        assertThat(notifications(mail(), s.agent().email())).as("mails to the agent who did it").isEmpty();
    }

    @Test
    @DisplayName("S4-AC-48 a customer closing and reopening their issue mails nobody: the assignee has the one "
            + "mail of the assignment, the reporter none")
    void customersCloseAndReopenMailNobody() {
        Scene s = steps.scene();
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();
        flow.assign(s.admin(), key, s.agent().accountId());
        awaitNotifications(s.agent(), 1);

        flow.close(s.customer(), key);
        flow.reopen(s.customer(), key);

        awaitALaterMail(s.admin());
        assertThat(notifications(mail(), s.agent().email())).as("mails to the assignee").hasSize(1);
        assertThat(notifications(mail(), s.customer().email())).as("mails to the reporter").isEmpty();
    }

    // S4-AC-49

    @Test
    @DisplayName("S4-AC-49 a new assignee is mailed once, with a console link; an agent who assigns themself, the "
            + "assignee before, and clearing the assignee mail nobody")
    void newAssigneeIsMailedOnce() {
        Scene s = steps.scene();
        Session second = agentOf(s, "Second");
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        flow.assign(s.admin(), key, s.agent().accountId());
        ReceivedMail m = awaitNotification(mail(), s.agent().email());
        flow.assign(second, key, second.accountId());
        flow.unassign(s.admin(), key);

        assertThat(m.to()).containsExactly(s.agent().email());
        assertThat(m.text()).as("text of the mail").contains(NotifySteps.consoleIssueLink(key))
                .doesNotContain(PORTAL_HOST);
        awaitALaterMail(s.admin());
        assertThat(notifications(mail(), s.agent().email())).as("mails to the agent an admin assigned").hasSize(1);
        assertThat(notifications(mail(), second.email())).as("mails to the agent who assigned themself").isEmpty();
        assertThat(notifications(mail(), s.customer().email())).as("mails to the reporter").isEmpty();
    }

    // S4-AC-50

    @Test
    @DisplayName("S4-AC-50 a comment that mentions an agent and an admin mails each of them once, with a console "
            + "link and without the name of who mentioned them")
    void mentionedStaffAreMailedOnce() {
        Scene s = steps.scene();
        Session second = agentOf(s, "Second");
        Session admin = steps.person(s.admin(), Role.ADMIN, "Adminname", null);
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        TalkFlow.mention(s.agent(), key, true, List.of(second.accountId(), admin.accountId()));

        for (Session mentioned : List.of(second, admin)) {
            ReceivedMail m = awaitNotification(mail(), mentioned.email());
            assertThat(m.to()).containsExactly(mentioned.email());
            assertThat(m.text()).as("text of the mail to %s", mentioned.role())
                    .contains(NotifySteps.consoleIssueLink(key)).doesNotContain(PORTAL_HOST);
            assertThat(everything(m)).as("the whole mail to %s", mentioned.role())
                    .doesNotContainIgnoringCase(s.agent().name()).doesNotContainIgnoringCase(s.agent().email());
        }
        awaitALaterMail(s.admin());
        assertThat(notifications(mail(), second.email())).as("mails to the mentioned agent").hasSize(1);
        assertThat(notifications(mail(), admin.email())).as("mails to the mentioned admin").hasSize(1);
        assertThat(notifications(mail(), s.agent().email())).as("mails to the agent who mentioned").isEmpty();
        assertThat(notifications(mail(), s.customer().email())).as("mails to the reporter of an internal comment")
                .isEmpty();
    }

    @Test
    @DisplayName("S4-AC-50 a public comment that mentions the reporter mails them once: the mention mail, with a "
            + "portal link, and no answer mail next to it")
    void mentionedReporterGetsTheMentionMailOnly() {
        Scene s = steps.scene();
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();

        TalkFlow.mention(s.agent(), key, false, List.of(s.customer().accountId()));

        ReceivedMail m = awaitNotification(mail(), s.customer().email());
        awaitALaterMail(s.admin());
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
