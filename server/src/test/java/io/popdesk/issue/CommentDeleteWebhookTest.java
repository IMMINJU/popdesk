package io.popdesk.issue;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Wait;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deleted comment's content with the team webhook on (setup "connected"): a customer's comment puts
 * a webhook in the send queue, and here the receiver answers 500, so that row is still waiting for its
 * next attempt when the comment is deleted. {@link CommentDeleteTest} looks at the same thing in the
 * setup without a webhook.
 */
class CommentDeleteWebhookTest extends ConnectedAppTest {

    private void awaitWebhooks(String key, int n) {
        Wait.until(n + " webhook request(s) about " + key, () -> webhook().containing(key).size() >= n ? key : null);
    }

    @Test
    @DisplayName("S4-AC-18 with the webhook of a customer's comment still in the send queue, a word of that "
            + "comment is in no row of any table once the comment is deleted, and in no webhook request")
    void deletedContentIsNowhereWithAWebhookWaiting() {
        Scene s = new IssueSteps(fixtures, clock).scene();
        String key = fixtures.fileIssue(s.customer(), s.code()).key();
        awaitWebhooks(key, 1);
        String word = IssueFlow.word();
        webhook().respondWith(500);
        Comment comment = fixtures.comment(s.customer(), key, "Call me on " + word + " please");
        // The comment's webhook was tried and refused: its row stays in the queue for the next attempt.
        awaitWebhooks(key, 2);
        assertThat(TalkFlow.tablesContaining(db, word)).as("columns holding the word before").isNotEmpty();

        TalkFlow.delete(s.customer(), key, comment.id());

        assertThat(TalkFlow.tablesContaining(db, word)).as("columns still holding the word").isEmpty();
        assertThat(TalkFlow.commentPage(s.customer(), key).text()).as("the portal list").doesNotContain(word);
        assertThat(webhook().containing(word)).as("webhook requests holding the word").isEmpty();
    }
}
