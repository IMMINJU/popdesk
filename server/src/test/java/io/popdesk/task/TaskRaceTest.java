package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Creating past a limit at the same time (S6B-AC-09, TM-58, {@code design.md} 0절 1): the 30th task of an issue
 * and the 100th note of a task. Only the number of each answer is counted, so it does not matter which request
 * wins. Each test makes its own issue.
 *
 * <p>Eight requests at once overlap only by chance. The held test makes the overlap sure: the insert of the last
 * row is kept waiting in the database ({@link HeldWrite}) while another write is sent. A write that counts and then
 * inserts without a lock counts the same number and is stored too.
 */
class TaskRaceTest extends BareAppTest {

    private static final int AT_ONCE = 8;

    /** How long the held insert waits when the other write neither finishes nor waits for a lock. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static String describe(List<ApiResponse> answers) {
        return answers.stream().map(a -> a.status() + (a.status() < 300 ? "" : " " + a.errorCode())).toList()
                .toString();
    }

    private static void assertOneWins(List<ApiResponse> answers, String code) {
        assertThat(answers.stream().filter(a -> a.status() == 201).count()).as("answers 201 in %s",
                describe(answers)).isEqualTo(1);
        assertThat(answers.stream().filter(a -> a.status() == 400 && code.equals(a.errorCode())).count())
                .as("answers 400 %s in %s", code, describe(answers)).isEqualTo(answers.size() - 1L);
    }

    @Test
    @DisplayName("S6B-AC-09 with 29 tasks on an issue, 8 created at the same time: exactly one is 201, the others are 400 TASK_LIMIT_EXCEEDED, and the issue has 30")
    void lastTaskAtOnce() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        for (int i = 0; i < 29; i++) {
            TaskSteps.create(w.agent(), issue.key());
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < AT_ONCE; i++) {
            String title = "Late " + i;
            calls.add(() -> TaskSteps.createRequest(admin, issue.key(), body("title", title)).send());
        }

        List<ApiResponse> answers = Concurrently.send(AT_ONCE, calls);

        assertOneWins(answers, "TASK_LIMIT_EXCEEDED");
        assertThat(TaskSteps.tasks(w.agent(), issue.key())).as("tasks of the issue").hasSize(30);
        assertThat(TaskSteps.openCount(w.agent(), issue.key())).as("openCount").isEqualTo(30);
    }

    @Test
    @DisplayName("S6B-AC-09 with 99 notes on a task, 8 added at the same time: exactly one is 201, the others are 400 TASK_NOTE_LIMIT_EXCEEDED, and the task has 100 notes")
    void lastNoteAtOnce() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        for (int i = 0; i < 99; i++) {
            TaskSteps.note(w.agent(), issue.key(), task, "Note " + i);
            if (i == 49) {
                TaskSteps.nextWindow(clock);
            }
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < AT_ONCE; i++) {
            String content = "Late " + i;
            calls.add(() -> TaskSteps.noteRequest(admin, issue.key(), task, content).send());
        }

        List<ApiResponse> answers = Concurrently.send(AT_ONCE, calls);

        assertOneWins(answers, "TASK_NOTE_LIMIT_EXCEEDED");
        assertThat(TaskSteps.notes(TaskSteps.task(w.agent(), issue.key(), task), "NOTE")).as("notes of the task")
                .hasSize(100);
    }

    /** The last write before a limit: the 30th task of an issue or the 100th note of a task. */
    enum Last {
        TASK,
        NOTE
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Last.class)
    @DisplayName("S6B-AC-09 while the insert of the 30th task or the 100th note is held in the database, another one is sent: exactly one is 201, the other is 400 TASK_LIMIT_EXCEEDED or TASK_NOTE_LIMIT_EXCEEDED, and the issue has 30 tasks or the task 100 notes")
    void limitHoldsWhileTheLastInsertIsHeld(Last last) {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = last == Last.NOTE ? id(TaskSteps.create(w.agent(), issue.key())) : 0;
        int before = last == Last.TASK ? 29 : 99;
        for (int i = 0; i < before; i++) {
            if (last == Last.TASK) {
                TaskSteps.create(w.agent(), issue.key());
            } else {
                TaskSteps.note(w.agent(), issue.key(), task, "Note " + i);
            }
            if (i == 49) {
                TaskSteps.nextWindow(clock);
            }
        }
        String held = Unique.text("Held");
        Function<String, ApiResponse> write = text -> last == Last.TASK
                ? TaskSteps.createRequest(admin, issue.key(), body("title", text)).send()
                : TaskSteps.noteRequest(admin, issue.key(), task, text).send();
        ApiResponse first;
        ApiResponse second;

        try (HeldWrite hold = new HeldWrite(db, postgres, last == Last.TASK ? "task" : "task_note", "insert",
                held)) {
            CompletableFuture<ApiResponse> one = CompletableFuture.supplyAsync(() -> write.apply(held));
            hold.awaitHeld("the insert of the last " + last);
            CompletableFuture<ApiResponse> other = CompletableFuture.supplyAsync(() -> write.apply("Not held"));
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the other write finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> other.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            first = Concurrently.finish(one, "the held write");
            second = Concurrently.finish(other, "the other write");
        }

        assertOneWins(List.of(first, second),
                last == Last.TASK ? "TASK_LIMIT_EXCEEDED" : "TASK_NOTE_LIMIT_EXCEEDED");
        if (last == Last.TASK) {
            assertThat(TaskSteps.tasks(w.agent(), issue.key())).as("tasks of the issue").hasSize(30);
        } else {
            assertThat(TaskSteps.notes(TaskSteps.task(w.agent(), issue.key(), task), "NOTE"))
                    .as("notes of the task").hasSize(100);
        }
    }
}
