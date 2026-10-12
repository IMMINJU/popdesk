package io.popdesk.issue;

import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The limit of 30 comments per account in 60 seconds (COM-8) counts adding and editing together, and
 * does not count deleting. The test clock stands still, so every request of a test falls in one
 * window.
 */
class CommentEditRateLimitTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }

    /** What the 31st request is. */
    enum Request {
        ADDING,
        EDITING
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Request.class)
    @DisplayName("S4-AC-09 after 15 comments added and 15 edited by one account within 60 seconds, the 31st request is 429 with Retry-After and changes nothing")
    void thirtyFirstOfAddingAndEditingIsLimited(Request thirtyFirst) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        List<Comment> added = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            added.add(fixtures.comment(w.reporter(), issue.key(), Unique.text("Added")));
        }
        for (Comment c : added) {
            TalkFlow.editContent(w.reporter(), issue.key(), c.id(), Unique.text("Edited"));
        }
        List<JsonNode> before = TalkFlow.comments(w.admin(), issue.key());

        ApiResponse limited = thirtyFirst == Request.ADDING
                ? TalkFlow.addRequest(w.reporter(), issue.key(), body("content", Unique.text("One more"))).send()
                : TalkFlow.editRequest(w.reporter(), issue.key(), added.getFirst().id(),
                        body("content", Unique.text("One more"))).send();

        assertRateLimited(limited);
        assertThat(before).hasSize(15);
        assertThat(TalkFlow.comments(w.admin(), issue.key())).as("the comments after the limited request")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("S4-AC-09 deleting is not counted: after 29 comments and 3 deletions the 30th comment is added, the 31st is 429, and a deletion after that is still 204")
    void deletingIsNotCounted() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        List<Comment> added = new ArrayList<>();
        for (int i = 0; i < 29; i++) {
            added.add(fixtures.comment(w.reporter(), issue.key(), Unique.text("Added")));
        }
        for (Comment c : added.subList(0, 3)) {
            TalkFlow.delete(w.reporter(), issue.key(), c.id());
        }

        ApiResponse thirtieth = TalkFlow.addRequest(w.reporter(), issue.key(), body("content", Unique.text("30th")))
                .send();
        ApiResponse thirtyFirst = TalkFlow.addRequest(w.reporter(), issue.key(),
                body("content", Unique.text("31st"))).send();
        ApiResponse deletion = TalkFlow.deleteRequest(w.reporter(), issue.key(), added.getLast().id()).send();

        assertThat(thirtieth.status()).as(thirtieth.request() + ": " + thirtieth.snippet()).isEqualTo(201);
        assertRateLimited(thirtyFirst);
        assertThat(deletion.status()).as("a deletion in the used-up window").isEqualTo(204);
        assertThat(TalkFlow.comment(w.admin(), issue.key(), added.getLast().id()).path("deleted").asBoolean(false))
                .as("the last comment is deleted").isTrue();
    }
}
