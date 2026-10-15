package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The limits of tasks and entries (S6B-AC-08, 23, TM-58): 30 tasks that are not deleted and 100 with the deleted
 * ones per issue, 100 notes that are not deleted and 1,000 entries of any kind per task. The rate limit of 60
 * writes is not under test here, so the clock moves to a new window every 50 writes.
 *
 * <p>The 1,000 entries are filled by inserting {@code CHANGE} rows into {@code task_note} ({@code design.md} 1절)
 * for a task the test created; going through the API would take 1,000 requests. This is the one place these
 * tests use the schema to make rows.
 */
class TaskLimitTest extends BareAppTest {

    private IssueFlow flow;
    private int writes;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Counts a write, and moves the clock to a new rate limit window every 50. */
    private void wrote() {
        if (++writes % 50 == 0) {
            TaskSteps.nextWindow(clock);
        }
    }

    private JsonNode create(Session staff, String key) {
        JsonNode task = TaskSteps.create(staff, key);
        wrote();
        return task;
    }

    private JsonNode note(Session staff, String key, long taskId, String content) {
        JsonNode note = TaskSteps.note(staff, key, taskId, content);
        wrote();
        return note;
    }

    // S6B-AC-08

    @Test
    @DisplayName("S6B-AC-08 with 30 tasks that are not deleted the 31st is 400 TASK_LIMIT_EXCEEDED; after one is deleted the next is 201")
    void thirtyTasksThatAreNotDeleted() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        List<JsonNode> made = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            made.add(create(w.agent(), issue.key()));
        }

        ApiResponse refused = TaskSteps.createRequest(w.agent(), issue.key(), body("title", "31st")).send();
        TaskSteps.assertRefused(refused, 400, "TASK_LIMIT_EXCEEDED");
        assertThat(TaskSteps.tasks(w.agent(), issue.key())).as("tasks after the 31st").hasSize(30);
        TaskSteps.delete(w.agent(), issue.key(), id(made.getFirst()));
        ApiResponse taken = TaskSteps.createRequest(w.agent(), issue.key(), body("title", "After a deletion")).send();

        assertThat(taken.status()).as(taken.request() + ": " + taken.snippet()).isEqualTo(201);
        assertThat(TaskSteps.openCount(w.agent(), issue.key())).as("openCount").isEqualTo(30);
    }

    @Test
    @DisplayName("S6B-AC-08 with 100 tasks of which 90 are deleted, creating is 400 TASK_LIMIT_EXCEEDED and the issue keeps 100")
    void hundredTasksWithTheDeletedOnes() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        for (int i = 0; i < 100; i++) {
            JsonNode task = create(w.agent(), issue.key());
            if (i < 90) {
                TaskSteps.delete(w.agent(), issue.key(), id(task));
            }
        }

        ApiResponse refused = TaskSteps.createRequest(w.agent(), issue.key(), body("title", "101st")).send();

        TaskSteps.assertRefused(refused, 400, "TASK_LIMIT_EXCEEDED");
        List<JsonNode> tasks = TaskSteps.tasks(w.agent(), issue.key());
        assertThat(tasks).as("tasks with the deleted ones").hasSize(100);
        assertThat(tasks.stream().filter(t -> t.path("deleted").asBoolean(false)).count()).as("deleted tasks")
                .isEqualTo(90);
    }

    // S6B-AC-23

    @Test
    @DisplayName("S6B-AC-23 with 100 notes that are not deleted the 101st is 400 TASK_NOTE_LIMIT_EXCEEDED; after one is deleted the next is 201")
    void hundredNotesThatAreNotDeleted() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(create(w.agent(), issue.key()));
        List<JsonNode> notes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            notes.add(note(w.agent(), issue.key(), task, "Note " + i));
        }

        ApiResponse refused = TaskSteps.noteRequest(w.agent(), issue.key(), task, "101st").send();
        TaskSteps.assertRefused(refused, 400, "TASK_NOTE_LIMIT_EXCEEDED");
        assertThat(TaskSteps.notes(TaskSteps.task(w.agent(), issue.key(), task), "NOTE")).as("notes after the 101st")
                .hasSize(100);
        TaskSteps.deleteNote(w.agent(), issue.key(), task, id(notes.getFirst()));
        ApiResponse taken = TaskSteps.noteRequest(w.agent(), issue.key(), task, "After a deletion").send();

        assertThat(taken.status()).as(taken.request() + ": " + taken.snippet()).isEqualTo(201);
    }

    /** What is tried on a task that has 1,000 entries. */
    enum Write {
        NOTE,
        TITLE,
        STATUS,
        ASSIGNEE,
        DUE_DATE
    }

    private static Map<String, Object> change(Write write, World w) {
        return switch (write) {
            case TITLE -> body("title", "Renamed");
            case STATUS -> body("status", "DONE");
            case ASSIGNEE -> body("assigneeId", w.agent().accountId());
            case DUE_DATE -> body("dueDate", "2099-12-31");
            case NOTE -> throw new AssertionError("A note is no change");
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S6B-AC-23 a task with 1,000 entries (a few notes, a deleted one, and changes) refuses a note and a change of its title, status, assignee, or due date with 400 TASK_NOTE_LIMIT_EXCEEDED, and nothing changes")
    void thousandEntries(Write write) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(create(w.agent(), issue.key()));
        note(w.agent(), issue.key(), task, "Kept");
        long deleted = id(note(w.agent(), issue.key(), task, "Deleted"));
        TaskSteps.deleteNote(w.agent(), issue.key(), task, deleted);
        TaskSteps.update(w.agent(), issue.key(), task, body("title", "Changed once"));
        long entries = db.count("select count(*) from task_note where task_id = ?", task);
        assertThat(entries).as("entries made through the API").isEqualTo(3);
        db.schemaFixture("insert into task_note (tenant_id, task_id, kind, author_id, content, field, old_value, "
                + "new_value, created_at) select t.tenant_id, t.id, 'CHANGE', t.created_by, '', 'STATUS', "
                + "case when g % 2 = 0 then 'OPEN' else 'DONE' end, case when g % 2 = 0 then 'DONE' else 'OPEN' end, "
                + "t.created_at from task t, generate_series(1, " + (1000 - entries) + ") g where t.id = " + task);
        JsonNode before = TaskSteps.task(w.agent(), issue.key(), task);
        assertThat(TaskSteps.notes(before)).as("entries of the task").hasSize(1000);

        ApiResponse refused = write == Write.NOTE
                ? TaskSteps.noteRequest(w.agent(), issue.key(), task, "One more").send()
                : TaskSteps.updateRequest(w.agent(), issue.key(), task, change(write, w)).send();

        TaskSteps.assertRefused(refused, 400, "TASK_NOTE_LIMIT_EXCEEDED");
        assertThat(TaskSteps.task(w.agent(), issue.key(), task)).as("the task after the refused write")
                .isEqualTo(before);
    }
}
