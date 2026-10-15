package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Tasks and the issue's status (S6B-AC-50, 52, 70, 71): a {@code CLOSED} issue takes no task writes but is read,
 * a reopened one takes them again, the console list counts the open tasks of each issue, and resolving or closing
 * an issue with open tasks is not held up.
 */
class TaskIssueStatusTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** An issue the agent took to {@code CONFIRMED}, with a task that has a note. */
    private record Scene(World w, Issue issue, long task, long note) {}

    private Scene scene() {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "CONFIRMED");
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        long note = id(TaskSteps.note(w.agent(), issue.key(), task, "Before closing"));
        return new Scene(w, issue, task, note);
    }

    // S6B-AC-50

    /** A write on a closed issue. */
    enum Write {
        CREATE_TASK,
        EDIT_TASK,
        ADD_NOTE,
        EDIT_NOTE
    }

    private static ApiResponse send(Write write, Session staff, Scene s) {
        String key = s.issue().key();
        return switch (write) {
            case CREATE_TASK -> TaskSteps.createRequest(staff, key, body("title", "After closing")).send();
            case EDIT_TASK -> TaskSteps.updateRequest(staff, key, s.task(), body("status", "DONE")).send();
            case ADD_NOTE -> TaskSteps.noteRequest(staff, key, s.task(), "After closing").send();
            case EDIT_NOTE -> TaskSteps.editNoteRequest(staff, key, s.task(), s.note(), "After closing").send();
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S6B-AC-50 on a CLOSED issue creating and editing a task and adding and editing a note are 400 ISSUE_CLOSED, the list is still read, and nothing changes")
    void closedIssueTakesNoWrite(Write write) {
        Scene s = scene();
        flow.close(s.w().reporter(), s.issue().key());
        JsonNode before = TaskSteps.list(s.w().agent(), s.issue().key());

        ApiResponse refused = send(write, s.w().agent(), s);

        TaskSteps.assertRefused(refused, 400, "ISSUE_CLOSED");
        assertThat(TaskSteps.list(s.w().agent(), s.issue().key())).as("the tasks after the refused write")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("S6B-AC-50, S6B-AC-04 on a CLOSED issue an assignee who may not be one is 400 INVALID_INPUT assigneeId, checked before ISSUE_CLOSED")
    void inputIsCheckedBeforeTheStatus() {
        Scene s = scene();
        flow.close(s.w().reporter(), s.issue().key());

        ApiResponse creating = TaskSteps.createRequest(s.w().agent(), s.issue().key(),
                body("title", "After closing", "assigneeId", s.w().agentElsewhere().accountId())).send();
        ApiResponse editing = TaskSteps.updateRequest(s.w().agent(), s.issue().key(), s.task(),
                body("assigneeId", s.w().agentElsewhere().accountId())).send();

        TaskSteps.assertInvalid(creating, "assigneeId", "UNKNOWN_VALUE");
        TaskSteps.assertInvalid(editing, "assigneeId", "UNKNOWN_VALUE");
        TaskSteps.assertRefused(send(Write.EDIT_TASK, s.w().agent(), s), 400, "ISSUE_CLOSED");
    }

    // S6B-AC-52

    @Test
    @DisplayName("S6B-AC-52 reopening a CLOSED issue keeps its tasks and notes, and they take writes again")
    void reopenedIssueTakesWritesAgain() {
        Scene s = scene();
        String key = s.issue().key();
        flow.close(s.w().reporter(), key);
        JsonNode closed = TaskSteps.list(s.w().agent(), key);

        flow.reopen(s.w().agent(), key);

        assertThat(TaskSteps.list(s.w().agent(), key)).as("the tasks after reopening").isEqualTo(closed);
        for (Write write : Write.values()) {
            ApiResponse answer = send(write, s.w().agent(), s);
            assertThat(answer.status()).as(answer.request() + ": " + answer.snippet()).isBetween(200, 201);
        }
        JsonNode task = TaskSteps.task(s.w().agent(), key, s.task());
        assertThat(text(task, "status")).isEqualTo("DONE");
        assertThat(TaskSteps.notes(task, "NOTE").stream().map(n -> text(n, "content")).toList())
                .containsExactly("After closing", "After closing");
    }

    // S6B-AC-70

    @Test
    @DisplayName("S6B-AC-70 each item of the console issue list has openTaskCount: the tasks that are OPEN and not deleted, 0 without tasks")
    void consoleListCountsOpenTasks() {
        World w = flow.world();
        Issue counted = fixtures.fileIssue(w.reporter(), w.code());
        Issue without = fixtures.fileIssue(w.reporter(), w.code());
        for (int i = 0; i < 3; i++) {
            TaskSteps.create(w.agent(), counted.key());
        }
        long done = id(TaskSteps.create(w.agent(), counted.key()));
        TaskSteps.update(w.agent(), counted.key(), done, body("status", "DONE"));
        TaskSteps.delete(w.agent(), counted.key(), id(TaskSteps.create(w.agent(), counted.key())));

        List<JsonNode> items = List.copyOf(IssueFlow.list(w.agent()).json().path("items").values());

        assertThat(count(items, counted.key())).as("openTaskCount of the issue with tasks").isEqualTo(3);
        assertThat(count(items, without.key())).as("openTaskCount of the issue without").isZero();
    }

    private static long count(List<JsonNode> items, String key) {
        JsonNode item = items.stream().filter(i -> key.equals(text(i, "key"))).findFirst()
                .orElseThrow(() -> new AssertionError("Issue " + key + " is not in the console list"));
        if (!item.path("openTaskCount").isIntegralNumber()) {
            throw new AssertionError("No integer openTaskCount in " + item);
        }
        return item.path("openTaskCount").asLong();
    }

    // S6B-AC-71

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RESOLVED", "CLOSED"})
    @DisplayName("S6B-AC-71 an issue with open tasks is resolved by staff or closed by its reporter, and the tasks stay OPEN and counted")
    void openTasksDoNotHoldTheIssue(String status) {
        Scene s = scene();
        String key = s.issue().key();
        TaskSteps.create(s.w().agent(), key);

        if (status.equals("RESOLVED")) {
            flow.changeStatus(s.w().agent(), key, "IN_PROGRESS");
            flow.changeStatus(s.w().agent(), key, "RESOLVED");
        } else {
            flow.close(s.w().reporter(), key);
        }

        assertThat(text(flow.detail(s.w().agent(), key), "status")).isEqualTo(status);
        assertThat(TaskSteps.tasks(s.w().agent(), key).stream().map(t -> text(t, "status")).toList())
                .as("status of the tasks").containsExactly("OPEN", "OPEN");
        assertThat(TaskSteps.openCount(s.w().agent(), key)).as("openCount").isEqualTo(2);
    }
}
