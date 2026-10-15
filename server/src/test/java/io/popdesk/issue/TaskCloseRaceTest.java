package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.time;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Editing a task while its issue is being closed (S6B-AC-51, STATUS-7), the way {@link LabelCloseRaceTest} makes
 * the two overlap: the closing request is kept waiting at its UPDATE of the issue row ({@link HeldWrite}), the
 * clock moves a second, the task's title is edited, and the closing is let go once the edit has finished or waits
 * for a lock.
 *
 * <p>Which request wins is not fixed. An edit that was stored must have been made before the closing: its
 * {@code CHANGE} entry is not later than {@code closedAt}. An edit that was refused is 400 {@code ISSUE_CLOSED}
 * and left the task as it was. An edit that does not lock the issue row reads the status before the closing is
 * done, so it is stored a second after the closing.
 */
class TaskCloseRaceTest extends BareAppTest {

    /** How long the closing request is held when the edit neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    @Test
    @DisplayName("S6B-AC-51 a task edited while its issue is closed is changed only if the edit came before the closing; otherwise the edit is 400 ISSUE_CLOSED and the task stays as it was")
    void editOverlapsTheClosing() {
        World w = flow.world();
        String title = Unique.text("Task race");
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(title), w.agent(), "CONFIRMED");
        String issueTable = new IssueTables(db).find(w.company().legalName(), title).issue().table().name();
        long task = TaskSteps.id(TaskSteps.create(w.agent(), issue.key(), "Before the race"));
        JsonNode before = TaskSteps.task(w.agent(), issue.key(), task);
        ApiResponse closing;
        ApiResponse editing;

        try (HeldWrite hold = new HeldWrite(db, postgres, issueTable, "update", title)) {
            CompletableFuture<ApiResponse> close = CompletableFuture.supplyAsync(
                    () -> IssueFlow.closeRequest(w.reporter(), issue.key()).send());
            hold.awaitHeld("the UPDATE that closes the issue");
            flow.tick();
            CompletableFuture<ApiResponse> edit = CompletableFuture.supplyAsync(
                    () -> TaskSteps.updateRequest(w.agent(), issue.key(), task, TaskSteps.body("title", "In the race"))
                            .send());
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the edit finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> edit.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            closing = Concurrently.finish(close, "the closing request");
            editing = Concurrently.finish(edit, "the edit");
        }

        assertThat(closing.status()).as("closing: " + closing.snippet()).isEqualTo(200);
        assertThat(editing.status()).as("edit: " + editing.snippet()).isLessThan(500);
        JsonNode after = flow.detail(w.agent(), issue.key());
        assertThat(text(after, "status")).as("status after both requests").isEqualTo("CLOSED");
        Instant closedAt = time(after, "closedAt");
        assertThat(closedAt).as("closedAt after both requests").isNotNull();
        JsonNode taskAfter = TaskSteps.task(w.agent(), issue.key(), task);
        if (editing.status() == 200) {
            assertThat(TaskSteps.text(taskAfter, "title")).as("title of the edited task").isEqualTo("In the race");
            List<JsonNode> changes = TaskSteps.changes(taskAfter);
            assertThat(changes).as("changes of the task").hasSize(TaskSteps.changes(before).size() + 1);
            assertThat(time(changes.getLast(), "createdAt")).as("time of the change, not after the closing")
                    .isBeforeOrEqualTo(closedAt);
        } else {
            TaskSteps.assertRefused(editing, 400, "ISSUE_CLOSED");
            assertThat(taskAfter).as("the task after the refused edit").isEqualTo(before);
        }
    }
}
