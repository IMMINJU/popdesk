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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Creating past a limit at the same time (S6B-AC-09, TM-58, {@code design.md} 0절 1): the 30th task of an issue
 * and the 100th note of a task. Only the number of each answer is counted, so it does not matter which request
 * wins. Each test makes its own issue.
 */
class TaskRaceTest extends BareAppTest {

    private static final int AT_ONCE = 8;

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
}
