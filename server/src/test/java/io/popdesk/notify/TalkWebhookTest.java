package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Editing a comment, deleting one, and mentioning someone send nothing to the team webhook (setup
 * "connected"). What the six webhook messages say is fixed by the snapshot tests of the slices that
 * made them.
 */
class TalkWebhookTest extends ConnectedAppTest {

    @Test
    @DisplayName("S4-AC-59 editing and deleting comments and mentioning an agent post no webhook: the issue has "
            + "the messages of its filing and of the customer's comments before and after, and no other")
    void editsDeletesAndMentionsPostNoWebhook() {
        NotifySteps steps = new NotifySteps(fixtures);
        Scene s = steps.scene();
        Session second = steps.person(s.admin(), Role.AGENT, "Second", null);
        fixtures.assignAgent(s.admin(), s.code(), second.accountId());
        String key = fixtures.fileIssue(s.customer(), s.code()).key();
        Comment question = fixtures.comment(s.customer(), key, Unique.text("Question"));
        awaitWebhooks(webhook(), key, 2);

        TalkFlow.editContent(s.customer(), key, question.id(), Unique.text("Edited question"));
        Comment note = TalkFlow.mention(s.agent(), key, true, List.of(second.accountId()));
        TalkFlow.editContent(s.agent(), key, note.id(), Unique.text("Edited note"));
        TalkFlow.editMentions(s.agent(), key, note.id(), List.of());
        TalkFlow.editMentions(s.agent(), key, note.id(), List.of(second.accountId()));
        TalkFlow.delete(s.agent(), key, note.id());
        TalkFlow.delete(s.customer(), key, question.id());

        // A later webhook of the same issue: by then a message for anything above would be there.
        fixtures.comment(s.customer(), key, Unique.text("Later question"));
        awaitWebhooks(webhook(), key, 3);
        assertThat(webhook().containing(key)).as("webhook messages about %s", key).hasSize(3);
    }
}
