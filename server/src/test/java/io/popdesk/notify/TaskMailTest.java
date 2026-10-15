package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static io.popdesk.notify.NotifySteps.everything;
import static io.popdesk.notify.NotifySteps.hasHangul;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.task.TaskSteps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mail of a task assignee in English (slice 6b, S6B-AC-34; SMTP on, GreenMail, installation language
 * en). The snapshot is {@code notify/__snapshots__/en-mail-task-assigned.txt}, in the form of
 * {@link TalkMails#snapshotForm}: the link to the task as {@code {link}}, the issue key as {@code {key}}.
 * The Korean mail and what happens to a queued one are {@link KoreanTaskMailTest}.
 */
class TaskMailTest extends ConnectedAppTest {

    /** The title of the task of a mail snapshot. */
    static final String TASK_TITLE = "Call the customer about the toner";

    private NotifySteps steps;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
    }

    /** The console link of a task: its issue's page with {@code #task-{id}}. */
    static String taskLink(String key, long taskId) {
        return NotifySteps.consoleIssueLink(key) + "#task-" + taskId;
    }

    @Test
    @DisplayName("S6B-AC-34 the task mail in English: subject [key] Task assigned: and the task title, the task "
            + "title, the issue title, and the console link to the task in the text, no word of a note or of the "
            + "issue's content, and the snapshot text")
    void mailInEnglish() {
        Scene s = steps.scene();
        Session assigner = steps.person(s.admin(), Role.ADMIN, "Assigner", null);
        String contentWord = IssueFlow.word();
        String noteWord = IssueFlow.word();
        String key = TalkMails.issue(fixtures, s, contentWord).key();
        long task = id(TaskSteps.create(assigner, key, TASK_TITLE));
        TaskSteps.note(assigner, key, task, "Ask about " + noteWord + " first.");

        TaskSteps.update(assigner, key, task, body("assigneeId", s.agent().accountId()));

        ReceivedMail m = awaitNotification(mail(), s.agent().email());
        String link = taskLink(key, task);
        assertThat(m.to()).as("To").containsExactly(s.agent().email());
        assertThat(m.subject()).as("subject").isEqualTo("[" + key + "] Task assigned: " + TASK_TITLE);
        assertThat(m.text()).as("text of the mail").contains(link).contains(TASK_TITLE)
                .contains(WebhookSnapshots.TITLE.replace('\n', ' ')).doesNotContain(PORTAL_HOST);
        assertThat(everything(m)).as("the whole mail").doesNotContainIgnoringCase(noteWord)
                .doesNotContainIgnoringCase(contentWord);
        assertThat(hasHangul(m.subject() + m.text())).as("Korean in an en mail: " + m.subject() + "\n" + m.text())
                .isFalse();
        WebhookSnapshots.assertMatches("en-mail-task-assigned.txt", TalkMails.snapshotForm(m, key, link, "{key}"));
    }
}
