package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.notify.TalkMails.Caused;
import io.popdesk.notify.TalkMails.Mail;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mails of slice 4 where the installation language is Korean, and what happens to a queued mail
 * when its recipient or its comment changes before it goes out. A mail is kept in the queue by closing
 * the {@link SmtpGate}: the first attempt fails, the row stays, the change is made, and the retry comes
 * 1 second later on the app's clock ({@link KoreanOnBehalfMailTest}).
 *
 * <p>"Not sent" is read after the mail's row is gone and a later mail arrived.
 */
class KoreanTalkMailTest extends GatedMailTest {

    private NotifySteps steps;
    private IssueFlow flow;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
        rows = new Rows(db);
    }

    /** The one mail row the request added for the recipient, read while the gate holds its mail. */
    private List<Row> heldMailRow(String queueTable, Snapshot before, Session recipient) {
        gate().awaitHeldTo(recipient.email());
        List<Row> mailRows = TalkMails.mailRowsTo(rows.added(before), queueTable, recipient);
        assertThat(mailRows).as("mail rows the request added for %s", recipient.email()).hasSize(1);
        return mailRows;
    }

    /**
     * Opens the gate and lets the retry come; the mail's row must go, and after a later mail arrived
     * the recipient has none and the mail server saw the one attempt the gate held.
     */
    private void assertDropped(Session admin, List<Row> mailRows, Session recipient) {
        gate().pass();
        clock.advance(Duration.ofSeconds(1));
        rows.awaitGone("the mail row of " + recipient.email() + " handled", mailRows);
        TalkFlow.awaitALaterMail(fixtures, admin);
        assertThat(notifications(mail(), recipient.email())).as("mails to %s", recipient.email()).isEmpty();
        assertThat(gate().held()).as("attempts that reached the mail server").filteredOn(h -> h.isTo(recipient.email()))
                .hasSize(1);
    }

    // S4-AC-52, S4-AC-53

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mail.class)
    @DisplayName("S4-AC-52 S4-AC-53 each new mail in Korean: the snapshot text, the link of the recipient's app, "
            + "[key] in the subject, 지원팀 for a customer, and nothing of the staff who did it or of the content")
    void mailInKorean(Mail mail) {
        Caused c = TalkMails.cause(mail, fixtures, flow, steps);

        ReceivedMail m = awaitNotification(mail(), c.recipient().email());

        TalkMails.assertLinksAndHides(m, c);
        if (c.toCustomer()) {
            assertThat(m.text()).as("text of a mail to a customer").contains("지원팀");
        }
        assertThat(hasHangul(m.subject())).as("Korean subject: " + m.subject()).isTrue();
        WebhookSnapshots.assertMatches(mail.snapshot("ko"), TalkMails.snapshotForm(m, c));
    }

    // S4-AC-54

    /** The recipient of a queued mail, and what happens to them before it goes out. */
    enum Gone {
        NEW_ASSIGNEE_SUSPENDED,
        REPORTER_SUSPENDED,
        NEW_ASSIGNEE_OFF_THE_COMPANY,
        MENTIONED_AGENT_OFF_THE_COMPANY,
        ADMIN_OF_A_MEMBERSHIP_REQUEST_SUSPENDED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gone.class)
    @DisplayName("S4-AC-54 S4-AC-51 a queued new mail is not sent, and its row is deleted, when its recipient was "
            + "suspended or taken off the company before it goes out; the mail of a membership request too, when "
            + "the admin it is for was suspended")
    void queuedMailForSomeoneWhoMayNotGetItIsDropped(Gone gone) {
        Scene s = steps.scene();
        Session second = TalkMails.agentOf(fixtures, steps, s, "Second");
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();
        flow.changeStatus(s.agent(), key, "IN_PROGRESS");
        // For the membership request: an admin of its own to mail, a manager who asks, and whom they ask for.
        Session approver = steps.person(s.admin(), Role.ADMIN, "Approver", null);
        Session manager = ManagerSteps.manager(fixtures, s.admin(), s.code());
        Session wanted = steps.person(s.admin(), Role.CUSTOMER, "Wanted", fixtures.createCompany(s.admin()).code());
        String queue = TalkMails.mailQueueTable(rows, gate(), clock, mail(), s.admin());
        gate().hold();
        Snapshot before = rows.snapshot();

        Session recipient = switch (gone) {
            case NEW_ASSIGNEE_SUSPENDED, NEW_ASSIGNEE_OFF_THE_COMPANY -> {
                flow.assign(s.admin(), key, second.accountId());
                yield second;
            }
            case REPORTER_SUSPENDED -> {
                flow.changeStatus(s.agent(), key, "RESOLVED");
                yield s.customer();
            }
            case MENTIONED_AGENT_OFF_THE_COMPANY -> {
                TalkFlow.mention(s.agent(), key, true, List.of(second.accountId()));
                yield second;
            }
            case ADMIN_OF_A_MEMBERSHIP_REQUEST_SUSPENDED -> {
                ManagerSteps.invited(manager, s.code(), wanted.email(), Unique.text("Typed"));
                yield approver;
            }
        };
        List<Row> mailRows = heldMailRow(queue, before, recipient);
        if (gone == Gone.NEW_ASSIGNEE_OFF_THE_COMPANY || gone == Gone.MENTIONED_AGENT_OFF_THE_COMPANY) {
            flow.unassignAgent(s.admin(), s.code(), recipient.accountId());
        } else {
            flow.suspend(s.admin(), recipient.accountId());
        }

        assertDropped(s.admin(), mailRows, recipient);
    }

    // S4-AC-16, S4-AC-55

    @ParameterizedTest(name = "the mail of kind {0}")
    @ValueSource(strings = {"STAFF_COMMENT", "MENTIONED"})
    @DisplayName("S4-AC-16 S4-AC-55 the queued mail of a comment (a staff answer, a mention) is not sent, and its "
            + "row is deleted, when the comment was deleted before it goes out")
    void queuedMailOfADeletedCommentIsDropped(String kind) {
        Scene s = steps.scene();
        Session second = TalkMails.agentOf(fixtures, steps, s, "Second");
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();
        String queue = TalkMails.mailQueueTable(rows, gate(), clock, mail(), s.admin());
        boolean answer = kind.equals("STAFF_COMMENT");
        Session recipient = answer ? s.customer() : second;
        gate().hold();
        Snapshot before = rows.snapshot();

        Comment comment = answer ? fixtures.comment(s.agent(), key, Unique.text("Answer"))
                : TalkFlow.mention(s.agent(), key, true, List.of(second.accountId()));
        List<Row> mailRows = heldMailRow(queue, before, recipient);
        TalkFlow.delete(s.agent(), key, comment.id());

        assertDropped(s.admin(), mailRows, recipient);
    }

    // S4-AC-56

    @Test
    @DisplayName("S4-AC-56 the queued mail of a mention is not sent, and its row is deleted, when an edit took the "
            + "person out of the mentions before it goes out; their inbox item goes too")
    void queuedMailOfARemovedMentionIsDropped() {
        Scene s = steps.scene();
        Session second = TalkMails.agentOf(fixtures, steps, s, "Second");
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();
        String queue = TalkMails.mailQueueTable(rows, gate(), clock, mail(), s.admin());
        gate().hold();
        Snapshot before = rows.snapshot();

        Comment comment = TalkFlow.mention(s.agent(), key, true, List.of(second.accountId()));
        List<Row> mailRows = heldMailRow(queue, before, second);
        assertThat(TalkFlow.inboxOf(second, key)).as("items of the mentioned agent before the edit").hasSize(1);
        TalkFlow.editMentions(s.agent(), key, comment.id(), List.of());

        assertDropped(s.admin(), mailRows, second);
        assertThat(TalkFlow.inboxOf(second, key)).as("items of the agent taken out of the mentions").isEmpty();
    }
}
