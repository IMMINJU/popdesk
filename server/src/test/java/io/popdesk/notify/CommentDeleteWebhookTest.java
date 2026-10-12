package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deleted comment's content with the team webhook on (setup "connected"): a customer's comment puts
 * a webhook in the send queue, and here the receiver answers 500, so that row is still waiting for its
 * next attempt when the comment is deleted. {@code issue.CommentDeleteTest} looks at the same thing in
 * the setup without a webhook. It lives in this package for {@link Rows}.
 *
 * <p>Deleting the comment leaves the waiting row alone: the webhook of a comment holds none of its
 * content and still goes out. After the test the receiver answers 204 again and the row is sent, as
 * {@link RetryTest} does, so that no row stays behind for a test that moves the clock by a day.
 */
class CommentDeleteWebhookTest extends ConnectedAppTest {

    /** The queue row of the comment's webhook that the test left waiting, if it got that far. */
    private Row waiting;

    @AfterEach
    void sendWhatIsWaiting() {
        webhook().respondWith(204);
        if (waiting != null) {
            clock.advance(Duration.ofSeconds(1));
            new Rows(db).awaitGone("the webhook row of the comment deleted after the send that worked",
                    List.of(waiting));
        }
    }

    @Test
    @DisplayName("S4-AC-18 with the webhook of a customer's comment still in the send queue, a word of that "
            + "comment is in no row of any table once the comment is deleted, and in no webhook request")
    void deletedContentIsNowhereWithAWebhookWaiting() {
        Rows rows = new Rows(db);
        Scene s = new NotifySteps(fixtures).scene();
        String key = fixtures.fileIssue(s.customer(), s.code()).key();
        awaitWebhooks(webhook(), key, 1);
        String word = IssueFlow.word();
        webhook().respondWith(500);
        Snapshot before = rows.snapshot();
        Comment comment = fixtures.comment(s.customer(), key, "Call me on " + word + " please");
        Instant firstFailure = clock.instant();
        // The comment's webhook was tried and refused: its row stays in the queue for the next attempt.
        awaitWebhooks(webhook(), key, 2);
        waiting = Wait.until("the queue row of the comment's webhook with its next attempt 1 second later", () -> {
            List<Row> added = rows.added(before).stream()
                    .filter(r -> !r.columnsAt(firstFailure.plusSeconds(1)).isEmpty()).toList();
            return added.size() == 1 ? added.getFirst() : null;
        });
        assertThat(TalkFlow.tablesContaining(db, word)).as("columns holding the word before").isNotEmpty();

        TalkFlow.delete(s.customer(), key, comment.id());

        assertThat(TalkFlow.tablesContaining(db, word)).as("columns still holding the word").isEmpty();
        assertThat(TalkFlow.commentPage(s.customer(), key).text()).as("the portal list").doesNotContain(word);
        assertThat(webhook().containing(word)).as("webhook requests holding the word").isEmpty();
    }
}
