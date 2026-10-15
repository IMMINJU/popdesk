package io.popdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A write to an issue while it is being deleted (S7A-AC-12, STATUS-7), the way {@code issue.LabelCloseRaceTest} makes
 * two requests overlap: the delete is kept waiting at its UPDATE of the issue row ({@link HeldWrite}), with the row
 * lock it took; the clock moves a second; the write is sent; and the delete is let go once the write has finished or
 * waits for a lock.
 *
 * <p>The delete took the issue and its time before the write was sent, so the write cannot come first in any order:
 * it must be the 404 of an issue in the trash, and the issue and what is under it must be as they were. A write that
 * does not lock the issue row (a file deleted without the lock) finishes while the delete waits; a status change
 * whose conditional UPDATE does not ask whether the issue is in the trash waits for the row and then changes the
 * issue in the trash. Both answer 2xx and change the issue.
 */
class TrashRaceTest extends BareAppTest {

    /** How long the delete is held when the write neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    /** The writes sent while the delete waits, each by the agent assigned to the issue. */
    enum Write {
        EDIT,
        STATUS,
        COMMENT,
        FILE_DELETE,
        TASK,
        LINK;

        ApiRequest request(TrashScene s, Issue third, long version) {
            String key = s.key();
            return switch (this) {
                case EDIT -> IssueFlow.editRequest(s.agent(), key, Map.of("title", "Edited in the race",
                        "version", version));
                case STATUS -> IssueFlow.statusRequest(s.agent(), key, "IN_PROGRESS");
                case COMMENT -> TalkFlow.addRequest(s.agent(), key, TalkFlow.body("content", "Comment in the race"));
                case FILE_DELETE -> s.agent().client().delete("/api/console/issues/" + key + "/attachments/"
                        + s.issueFile().id());
                case TASK -> TaskSteps.createRequest(s.agent(), key, TaskSteps.body("title", "Task in the race"));
                case LINK -> LinkSteps.createRequest(s.agent(), key, third.key(), LinkSteps.RELATED);
            };
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S7A-AC-12 an edit, a status change, a comment, a file deleted, a task, or a link sent while the delete holds the issue is 404 NOT_FOUND once the delete is done, and the issue in the trash and what is under it are as they were")
    void writeDuringTheDelete(Write write) {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        Issue third = fixtures.fileIssue(s.reporter(), s.code());
        long version = flow.version(s.agent(), s.key());
        Map<String, Object> fields = rows.fields(s.key());
        Map<String, List<Map<String, Object>>> under = rows.under(s.key());
        ApiResponse deleting;
        ApiResponse writing;

        try (HeldWrite hold = new HeldWrite(db, postgres, "issue", "update", s.title())) {
            CompletableFuture<ApiResponse> delete = CompletableFuture.supplyAsync(
                    () -> TrashSteps.deleteRequest(s.admin(), s.key()).send());
            hold.awaitHeld("the UPDATE that moves the issue to the trash");
            flow.tick();
            CompletableFuture<ApiResponse> sent = CompletableFuture.supplyAsync(
                    () -> write.request(s, third, version).send());
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the write finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> sent.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            deleting = Concurrently.finish(delete, "the delete");
            writing = Concurrently.finish(sent, "the write");
        }

        assertThat(deleting.status()).as("delete: " + deleting.snippet()).isEqualTo(204);
        assertThat(writing.status()).as("the write after the delete took the issue: " + writing.snippet())
                .isEqualTo(404);
        assertThat(writing.errorCode()).as("code of the write").isEqualTo("NOT_FOUND");
        assertThat(rows.fields(s.key())).as("the issue's fields in the trash").isEqualTo(fields);
        assertThat(rows.under(s.key())).as("what is under the issue in the trash").isEqualTo(under);
        assertThat(LinkSteps.links(s.admin(), third.key())).as("links of the third issue").isEmpty();
    }
}
