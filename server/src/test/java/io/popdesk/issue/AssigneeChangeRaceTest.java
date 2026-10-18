package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.trash.TrashRows;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * A write under an issue that waits for the issue row while a change of its assignee is not committed yet (S3-AC-24,
 * STATUS-7). The change of the assignee is kept waiting at its history INSERT ({@link HeldWrite} on
 * {@code issue_history}, the rows of this issue), after its UPDATE of the issue row and with that row's lock; a
 * comment is sent and is let wait for the row; then the change goes on and commits.
 *
 * <p>The comment locks the row with the issue's people joined to it. Under READ COMMITTED, PostgreSQL reads the
 * locked row again once the change committed, with the new assignee, but keeps the account row it joined first:
 * the assignee's ID is the new one and the name and role beside it are empty. A lock that reads those columns that
 * way answers 500. The change took the row before the comment was sent, so the comment comes after it in the one
 * order there is: it is stored, and the issue's assignee is the new one, with the name and role of that account.
 */
class AssigneeChangeRaceTest extends BareAppTest {

    /** Who the issue is assigned to before the change. */
    enum From {
        NOBODY,
        AN_AGENT
    }

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(From.class)
    @DisplayName("S3-AC-24 a comment that waits for the issue row while the assignee changes is stored once the change commits, without a 5xx, and the issue then has the new assignee with its name and role")
    void commentWaitingForTheAssigneeChange(From from) {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(Unique.text("Assignee race")), w.agent(),
                "CONFIRMED");
        String key = issue.key();
        if (from == From.AN_AGENT) {
            flow.assign(w.admin(), key, w.agent().accountId());
        }
        Session next = fixtures.agent(w.admin(), w.code());
        UUID id = new TrashRows(db).id(key);
        String marker = "Comment in the assignee race #" + Unique.number() + "#";
        ApiResponse assigning;
        ApiResponse commenting;

        try (HeldWrite hold = new HeldWrite(db, postgres, "issue_history", "insert", id.toString())) {
            CompletableFuture<ApiResponse> assign = CompletableFuture.supplyAsync(
                    () -> IssueFlow.assignRequest(w.admin(), key, next.accountId()).send());
            hold.awaitHeld("the history INSERT of the assignee change");
            CompletableFuture<ApiResponse> comment = CompletableFuture.supplyAsync(
                    () -> TalkFlow.addRequest(w.admin(), key, TalkFlow.body("content", marker)).send());
            Wait.until("the comment waits for a lock or finished",
                    () -> comment.isDone() || hold.waitingForALock() > 0 ? Boolean.TRUE : null);
            assertThat(comment.isDone()).as("the comment finished before the change of the assignee let the issue row go")
                    .isFalse();
            hold.release();
            assigning = Concurrently.finish(assign, "the assignee change");
            commenting = Concurrently.finish(comment, "the comment");
        }

        assertThat(assigning.status()).as("assignee change: " + assigning.snippet()).isEqualTo(200);
        assertThat(commenting.status()).as("comment after the assignee change: " + commenting.snippet())
                .isEqualTo(201);
        JsonNode after = flow.detail(w.admin(), key);
        assertThat(after.path("assignee").path("accountId").asLong(-1)).as("assignee after both requests")
                .isEqualTo(next.accountId());
        assertThat(text(after.path("assignee"), "name")).as("name of the assignee").isEqualTo(next.name());
        assertThat(text(after.path("assignee"), "role")).as("role of the assignee").isEqualTo("AGENT");
        ApiResponse comments = w.admin().client().get("/api/console/issues/" + key + "/comments").query("size", 100)
                .expect(200);
        assertThat(items(comments).stream().filter(c -> marker.equals(text(c, "content"))).count())
                .as("the comment under the issue").isEqualTo(1);
    }
}
