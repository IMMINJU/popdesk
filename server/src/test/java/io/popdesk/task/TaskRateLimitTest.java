package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The limit of 60 task writes per account in 60 seconds (S6B-AC-13, COM-8, TM-58) counts creating and editing
 * tasks and adding and editing notes together, and does not count deleting. The test clock stands still, so every
 * request of a test falls in one window.
 */
class TaskRateLimitTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static void assertRateLimited(ApiResponse response) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(429);
        assertThat(response.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).as("Retry-After").isNotBlank();
    }

    /** What the 61st request is. */
    enum Request {
        CREATE_TASK,
        EDIT_TASK,
        ADD_NOTE,
        EDIT_NOTE
    }

    /** 15 of each kind of write by the agent: 15 tasks, 15 edits of them, 15 notes, 15 edits of the notes. */
    private record Used(Issue issue, List<Long> tasks, List<Long> notes) {}

    private Used useTheWindow(World w) {
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        List<Long> tasks = new ArrayList<>();
        List<Long> notes = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tasks.add(id(TaskSteps.create(w.agent(), issue.key())));
        }
        for (long task : tasks) {
            TaskSteps.update(w.agent(), issue.key(), task, body("title", TaskSteps.title("Edited")));
        }
        for (long task : tasks) {
            notes.add(id(TaskSteps.note(w.agent(), issue.key(), task, "Note")));
        }
        for (int i = 0; i < 15; i++) {
            TaskSteps.editNote(w.agent(), issue.key(), tasks.get(i), notes.get(i), "Edited note");
        }
        return new Used(issue, tasks, notes);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Request.class)
    @DisplayName("S6B-AC-13 after 60 task writes of four kinds by one account within 60 seconds, the 61st write of any kind is 429 RATE_LIMITED with Retry-After and changes nothing")
    void sixtyFirstWriteIsLimited(Request sixtyFirst) {
        World w = flow.world();
        Used used = useTheWindow(w);
        String key = used.issue().key();
        List<JsonNode> before = TaskSteps.tasks(w.admin(), key);

        ApiResponse limited = switch (sixtyFirst) {
            case CREATE_TASK -> TaskSteps.createRequest(w.agent(), key, body("title", "61st")).send();
            case EDIT_TASK -> TaskSteps.updateRequest(w.agent(), key, used.tasks().getFirst(), body("title", "61st"))
                    .send();
            case ADD_NOTE -> TaskSteps.noteRequest(w.agent(), key, used.tasks().getFirst(), "61st").send();
            case EDIT_NOTE -> TaskSteps.editNoteRequest(w.agent(), key, used.tasks().getFirst(),
                    used.notes().getFirst(), "61st").send();
        };

        assertRateLimited(limited);
        assertThat(TaskSteps.tasks(w.admin(), key)).as("tasks after the limited request").isEqualTo(before);
        assertThat(TaskSteps.createRequest(fixtures.admin(w.admin()), key, body("title", "By another account")).send().status())
                .as("a write of another account in the same window").isEqualTo(201);
    }

    @Test
    @DisplayName("S6B-AC-13 deleting is not counted: with the window used up, deleting a note and a task is still 204")
    void deletingIsNotCounted() {
        World w = flow.world();
        Used used = useTheWindow(w);
        String key = used.issue().key();
        assertRateLimited(TaskSteps.createRequest(w.agent(), key, body("title", "61st")).send());

        ApiResponse note = TaskSteps.deleteNoteRequest(w.agent(), key, used.tasks().get(1), used.notes().get(1))
                .send();
        ApiResponse task = TaskSteps.deleteRequest(w.agent(), key, used.tasks().getLast()).send();

        assertThat(note.status()).as(note.request() + ": " + note.snippet()).isEqualTo(204);
        assertThat(task.status()).as(task.request() + ": " + task.snippet()).isEqualTo(204);
        assertThat(TaskSteps.task(w.admin(), key, used.tasks().getLast()).path("deleted").asBoolean(false))
                .as("the last task is deleted").isTrue();
        assertThat(TaskSteps.entry(TaskSteps.task(w.admin(), key, used.tasks().get(1)), used.notes().get(1))
                .path("deleted").asBoolean(false)).as("the note is deleted").isTrue();
    }
}
