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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Writing to a task while its issue is being closed (S6B-AC-51, STATUS-7), the way {@link LabelCloseRaceTest} makes
 * the two overlap: the closing request is kept waiting at its UPDATE of the issue row ({@link HeldWrite}), the
 * clock moves a second, the write is sent (an edit of the task's title, or a note), and the closing is let go once
 * the write has finished or waits for a lock.
 *
 * <p>Which request wins is not fixed. A write that was stored must have been made before the closing: its entry is
 * not later than {@code closedAt}. A write that was refused is 400 {@code ISSUE_CLOSED} and left the task as it
 * was. A write that does not lock the issue row reads the status before the closing is done, so it is stored a
 * second after the closing.
 */
class TaskCloseRaceTest extends BareAppTest {

    /** How long the closing request is held when the write neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** An issue of the world in CONFIRMED with a task, and the task as it was before the race. */
    private record Scene(World w, Issue issue, String title, String issueTable, long task, JsonNode before) {}

    private Scene scene() {
        World w = flow.world();
        String title = Unique.text("Task race");
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(title), w.agent(), "CONFIRMED");
        String issueTable = new IssueTables(db).find(w.company().legalName(), title).issue().table().name();
        long task = TaskSteps.id(TaskSteps.create(w.agent(), issue.key(), "Before the race"));
        return new Scene(w, issue, title, issueTable, task, TaskSteps.task(w.agent(), issue.key(), task));
    }

    /**
     * Closes the issue and sends the write while the closing is held; gives the write's answer once the closing
     * was 200 and the issue is CLOSED.
     */
    private ApiResponse overlap(Scene s, Supplier<ApiResponse> write) {
        ApiResponse closing;
        ApiResponse writing;
        try (HeldWrite hold = new HeldWrite(db, postgres, s.issueTable(), "update", s.title())) {
            CompletableFuture<ApiResponse> close = CompletableFuture.supplyAsync(
                    () -> IssueFlow.closeRequest(s.w().reporter(), s.issue().key()).send());
            hold.awaitHeld("the UPDATE that closes the issue");
            flow.tick();
            CompletableFuture<ApiResponse> sent = CompletableFuture.supplyAsync(write);
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the write finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> sent.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            closing = Concurrently.finish(close, "the closing request");
            writing = Concurrently.finish(sent, "the write");
        }

        assertThat(closing.status()).as("closing: " + closing.snippet()).isEqualTo(200);
        assertThat(writing.status()).as("write: " + writing.snippet()).isLessThan(500);
        assertThat(text(flow.detail(s.w().agent(), s.issue().key()), "status")).as("status after both requests")
                .isEqualTo("CLOSED");
        return writing;
    }

    private Instant closedAt(Scene s) {
        Instant closedAt = time(flow.detail(s.w().agent(), s.issue().key()), "closedAt");
        assertThat(closedAt).as("closedAt after both requests").isNotNull();
        return closedAt;
    }

    @Test
    @DisplayName("S6B-AC-51 a task edited while its issue is closed is changed only if the edit came before the closing; otherwise the edit is 400 ISSUE_CLOSED and the task stays as it was")
    void editOverlapsTheClosing() {
        Scene s = scene();

        ApiResponse editing = overlap(s, () -> TaskSteps.updateRequest(s.w().agent(), s.issue().key(), s.task(),
                TaskSteps.body("title", "In the race")).send());

        JsonNode taskAfter = TaskSteps.task(s.w().agent(), s.issue().key(), s.task());
        if (editing.status() == 200) {
            assertThat(TaskSteps.text(taskAfter, "title")).as("title of the edited task").isEqualTo("In the race");
            List<JsonNode> changes = TaskSteps.changes(taskAfter);
            assertThat(changes).as("changes of the task").hasSize(1);
            assertThat(time(changes.getLast(), "createdAt")).as("time of the change, not after the closing")
                    .isBeforeOrEqualTo(closedAt(s));
        } else {
            TaskSteps.assertRefused(editing, 400, "ISSUE_CLOSED");
            assertThat(taskAfter).as("the task after the refused edit").isEqualTo(s.before());
        }
    }

    @Test
    @DisplayName("S6B-AC-51 a note added while the issue is closed is kept only if it came before the closing; otherwise adding it is 400 ISSUE_CLOSED and the task stays as it was")
    void noteOverlapsTheClosing() {
        Scene s = scene();

        ApiResponse adding = overlap(s,
                () -> TaskSteps.noteRequest(s.w().agent(), s.issue().key(), s.task(), "In the race").send());

        JsonNode taskAfter = TaskSteps.task(s.w().agent(), s.issue().key(), s.task());
        if (adding.status() == 201) {
            List<JsonNode> notes = TaskSteps.notes(taskAfter, "NOTE");
            assertThat(notes).as("notes of the task").hasSize(1);
            assertThat(TaskSteps.text(notes.getFirst(), "content")).as("content of the note").isEqualTo("In the race");
            assertThat(time(notes.getFirst(), "createdAt")).as("time of the note, not after the closing")
                    .isBeforeOrEqualTo(closedAt(s));
        } else {
            TaskSteps.assertRefused(adding, 400, "ISSUE_CLOSED");
            assertThat(taskAfter).as("the task after the refused note").isEqualTo(s.before());
        }
    }
}
