package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.notify.NotifySteps.notifications;
import static io.popdesk.notify.TaskMailTest.TASK_TITLE;
import static io.popdesk.notify.TaskMailTest.taskLink;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.title;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The mail of a task assignee where the installation language is Korean, and what happens to it while it
 * waits in the queue (slice 6b, S6B-AC-34). A mail is kept in the queue by closing the {@link SmtpGate}: the
 * first attempt fails, the change is made, and the retry comes 1 second later on the app's clock, as in
 * {@link KoreanTalkMailTest}. The retry is where the app must look again at the recipient and the task.
 *
 * <p>"Not sent" is read after the mail's row is gone and a later mail arrived.
 */
class KoreanTaskMailTest extends GatedMailTest {

    private NotifySteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
    }

    /** The scene, an admin who does the task work, and two more agents of the company. */
    private record Desk(Scene s, Session assigner, Session second, Session third, String key) {}

    private Desk desk() {
        Scene s = steps.scene();
        Session assigner = steps.person(s.admin(), Role.ADMIN, "Assigner", null);
        Session second = TalkMails.agentOf(fixtures, steps, s, "Second");
        Session third = TalkMails.agentOf(fixtures, steps, s, "Third");
        String key = TalkMails.issue(fixtures, s, IssueFlow.word()).key();
        return new Desk(s, assigner, second, third, key);
    }

    private long mailRowsTo(Session recipient) {
        return db.count("select count(*) from outbox where channel = 'MAIL' and recipient_account_id = ?",
                recipient.accountId());
    }

    private void letTheRetryCome() {
        gate().pass();
        clock.advance(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("S6B-AC-34 the task mail in Korean: subject [key] 작업을 맡았습니다: and the task title, the console "
            + "link to the task, and the snapshot text")
    void mailInKorean() {
        Desk d = desk();
        long task = id(TaskSteps.create(d.assigner(), d.key(),
                body("title", TASK_TITLE, "assigneeId", d.second().accountId())));

        ReceivedMail m = awaitNotification(mail(), d.second().email());

        String link = taskLink(d.key(), task);
        assertThat(m.to()).as("To").containsExactly(d.second().email());
        assertThat(m.subject()).as("subject").isEqualTo("[" + d.key() + "] 작업을 맡았습니다: " + TASK_TITLE);
        assertThat(m.text()).as("text of the mail").contains(link).contains(WebhookSnapshots.TITLE);
        assertThat(hasHangul(m.text())).as("Korean text: " + m.text()).isTrue();
        WebhookSnapshots.assertMatches("ko-mail-task-assigned.txt",
                TalkMails.snapshotForm(m, d.key(), link, "{key}"));
    }

    /** What happens to the recipient or the task while the mail waits. */
    enum Gone {
        RECIPIENT_SUSPENDED,
        RECIPIENT_OFF_THE_COMPANY,
        ASSIGNEE_CHANGED,
        TASK_DELETED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gone.class)
    @DisplayName("S6B-AC-34 a queued task mail is not sent, and its row is deleted, when before it goes out its "
            + "recipient was suspended or taken off the company (still the task's assignee), the task went to "
            + "another person, or the task was deleted; the person the task went to gets theirs")
    void queuedTaskMailIsCheckedAgain(Gone gone) {
        Desk d = desk();
        long task = id(TaskSteps.create(d.assigner(), d.key(), title("Held")));
        gate().hold();
        TaskSteps.update(d.assigner(), d.key(), task, body("assigneeId", d.second().accountId()));
        gate().awaitHeldTo(d.second().email());

        switch (gone) {
            case RECIPIENT_SUSPENDED -> flow.suspend(d.s().admin(), d.second().accountId());
            case RECIPIENT_OFF_THE_COMPANY -> flow.unassignAgent(d.s().admin(), d.s().code(), d.second().accountId());
            case ASSIGNEE_CHANGED -> TaskSteps.update(d.assigner(), d.key(), task,
                    body("assigneeId", d.third().accountId()));
            case TASK_DELETED -> TaskSteps.delete(d.assigner(), d.key(), task);
        }
        if (gone == Gone.RECIPIENT_OFF_THE_COMPANY) {
            assertThat(TaskSteps.personId(TaskSteps.task(d.assigner(), d.key(), task), "assignee"))
                    .as("assignee of the task after the recipient was taken off the company")
                    .isEqualTo(d.second().accountId());
        }
        letTheRetryCome();

        Wait.untilAsserted("the mail row of the first recipient dropped",
                () -> assertThat(mailRowsTo(d.second())).isZero());
        if (gone == Gone.ASSIGNEE_CHANGED) {
            ReceivedMail theirs = awaitNotification(mail(), d.third().email());
            assertThat(theirs.subject()).as("subject of the mail of the person the task went to")
                    .contains("[" + d.key() + "]");
        }
        TalkFlow.awaitALaterMail(fixtures, d.s().admin());
        assertThat(notifications(mail(), d.second().email())).as("mails to the first recipient").isEmpty();
        assertThat(gate().held()).as("attempts that reached the mail server for the first recipient")
                .filteredOn(h -> h.isTo(d.second().email())).hasSize(1);
    }

    @Test
    @DisplayName("S6B-AC-34 the task title of a mail is read when it is sent: a task renamed while its mail waits "
            + "is mailed with the new title")
    void titleIsReadWhenTheMailIsSent() {
        Desk d = desk();
        String first = title("First");
        String renamed = title("Renamed");
        long task = id(TaskSteps.create(d.assigner(), d.key(), first));
        gate().hold();
        TaskSteps.update(d.assigner(), d.key(), task, body("assigneeId", d.second().accountId()));
        gate().awaitHeldTo(d.second().email());

        TaskSteps.update(d.assigner(), d.key(), task, body("title", renamed));
        letTheRetryCome();

        ReceivedMail m = awaitNotification(mail(), d.second().email());
        assertThat(m.subject()).as("subject").isEqualTo("[" + d.key() + "] 작업을 맡았습니다: " + renamed);
        assertThat(m.text()).as("text of the mail").contains(renamed).doesNotContain(first)
                .contains(taskLink(d.key(), task));
    }
}
