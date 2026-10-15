package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.personId;
import static io.popdesk.task.TaskSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Deleting a task (S6B-AC-10 to 12, COM-7): who may, the trace it leaves in the list, its notes and changes losing
 * their content in the answer and in the database ({@code task_note}, {@code design.md} 1절), deleting again, a
 * closed issue, and a deleted task taking no more writes and leaving the counts.
 */
class TaskDeleteTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Who deletes the task an agent created. */
    enum Deleter {
        CREATOR,
        ADMIN
    }

    /** The issue (in {@code status}), and a task the agent made with an assignee, a due date, a note, and a change. */
    private record Scene(World w, Issue issue, JsonNode task, String word, Session assignee) {}

    private Scene scene(String status) {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "CONFIRMED");
        Session assignee = fixtures.agent(w.admin(), w.code());
        String word = IssueFlow.word();
        JsonNode task = TaskSteps.create(w.agent(), issue.key(), body("title", "Call " + word, "assigneeId",
                assignee.accountId(), "dueDate", "2099-12-31"));
        TaskSteps.note(w.agent(), issue.key(), id(task), "Phone " + word + " after five");
        TaskSteps.update(w.agent(), issue.key(), id(task), body("title", "Ring " + word));
        if (status.equals("CLOSED")) {
            flow.close(w.reporter(), issue.key());
        }
        return new Scene(w, issue, task, word, assignee);
    }

    private static Session deleter(Deleter deleter, World w, Session admin) {
        return deleter == Deleter.CREATOR ? w.agent() : admin;
    }

    // S6B-AC-10

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleter.class)
    @DisplayName("S6B-AC-10 the creator or an admin deletes a task: 204, the list keeps it in its place as a trace with an empty title, no assignee, no due date, overdue false, no notes, and who deleted it and when; its note keeps no content and its change no values, in the database too")
    void creatorOrAdminDeletes(Deleter deleter) {
        Scene s = scene("CONFIRMED");
        World w = s.w();
        Session admin = fixtures.admin(w.admin());
        String key = s.issue().key();
        JsonNode first = TaskSteps.create(w.agent(), key);
        JsonNode last;
        assertThat(TalkFlow.tablesContaining(db, s.word())).as("columns holding the word before").isNotEmpty();
        Instant deletedAt = clock.advance(Duration.ofMinutes(5));

        ApiResponse deleted = TaskSteps.deleteRequest(deleter(deleter, w, admin), key, id(s.task())).send();
        last = TaskSteps.create(w.agent(), key);

        assertThat(deleted.status()).as(deleted.request() + ": " + deleted.snippet()).isEqualTo(204);
        assertThat(deleted.body()).as("body of the 204").isEmpty();
        List<JsonNode> tasks = TaskSteps.tasks(w.agent(), key);
        assertThat(tasks.stream().map(TaskSteps::id).toList()).as("tasks of the issue")
                .containsExactly(id(s.task()), id(first), id(last));
        JsonNode trace = tasks.getFirst();
        assertThat(trace.path("deleted").asBoolean(false)).as("deleted of %s", trace).isTrue();
        assertThat(text(trace, "title")).as("title of the trace").isEmpty();
        assertThat(trace.path("assignee").isNull()).as("assignee of %s", trace).isTrue();
        assertThat(trace.path("dueDate").isNull()).as("dueDate of %s", trace).isTrue();
        assertThat(trace.path("overdue").asBoolean(true)).as("overdue of the trace").isFalse();
        assertThat(TaskSteps.notes(trace)).as("notes of the trace").isEmpty();
        assertThat(IssueFlow.time(trace, "deletedAt")).as("deletedAt").isEqualTo(deletedAt);
        assertThat(personId(trace, "deletedBy")).as("deletedBy")
                .isEqualTo(deleter(deleter, w, admin).accountId());
        assertThat(TaskSteps.list(w.agent(), key).toString()).as("the task list").doesNotContain(s.word());
        List<Map<String, Object>> rows = db.rows(
                "select kind, content, old_value, new_value from task_note where task_id = ? order by id",
                id(s.task()));
        assertThat(rows).as("rows of the task's entries").isNotEmpty();
        for (Map<String, Object> row : rows) {
            assertThat(row.get("content")).as("content of %s", row).isEqualTo("");
            assertThat(row.get("old_value")).as("old_value of %s", row).isNull();
            assertThat(row.get("new_value")).as("new_value of %s", row).isNull();
        }
        assertThat(TalkFlow.tablesContaining(db, s.word())).as("columns still holding the word").isEmpty();
    }

    @Test
    @DisplayName("S6B-AC-10 deleting a deleted task again, by its creator and by an admin, is 204 and keeps the first time and the first person")
    void deletingAgainChangesNothing() {
        Scene s = scene("CONFIRMED");
        World w = s.w();
        String key = s.issue().key();
        clock.advance(Duration.ofMinutes(5));
        TaskSteps.delete(w.agent(), key, id(s.task()));
        JsonNode trace = TaskSteps.task(w.agent(), key, id(s.task()));
        clock.advance(Duration.ofMinutes(5));

        ApiResponse byCreator = TaskSteps.deleteRequest(w.agent(), key, id(s.task())).send();
        clock.advance(Duration.ofMinutes(5));
        ApiResponse byAdmin = TaskSteps.deleteRequest(fixtures.admin(w.admin()), key, id(s.task())).send();

        assertThat(byCreator.status()).as("the creator deletes again: " + byCreator.snippet()).isEqualTo(204);
        assertThat(byAdmin.status()).as("an admin deletes again: " + byAdmin.snippet()).isEqualTo(204);
        assertThat(TaskSteps.task(w.agent(), key, id(s.task()))).as("the trace after deleting again")
                .isEqualTo(trace);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleter.class)
    @DisplayName("S6B-AC-10 a task of a CLOSED issue is deleted by its creator or an admin: 204, the trace is in the list, and the issue stays CLOSED")
    void taskOfAClosedIssueIsDeleted(Deleter deleter) {
        Scene s = scene("CLOSED");
        World w = s.w();
        Session admin = fixtures.admin(w.admin());
        String key = s.issue().key();

        ApiResponse deleted = TaskSteps.deleteRequest(deleter(deleter, w, admin), key, id(s.task())).send();

        assertThat(deleted.status()).as(deleted.request() + ": " + deleted.snippet()).isEqualTo(204);
        JsonNode trace = TaskSteps.task(w.agent(), key, id(s.task()));
        assertThat(trace.path("deleted").asBoolean(false)).as("deleted of %s", trace).isTrue();
        assertThat(text(trace, "title")).isEmpty();
        assertThat(text(flow.detail(w.agent(), key), "status")).isEqualTo("CLOSED");
    }

    // S6B-AC-11

    @Test
    @DisplayName("S6B-AC-11 an agent who did not create the task, assigned to the company and the task's assignee, gets 403 FORBIDDEN for deleting it, and the task stays")
    void otherAgentsMayNotDelete() {
        Scene s = scene("CONFIRMED");
        World w = s.w();
        String key = s.issue().key();
        JsonNode before = TaskSteps.task(w.agent(), key, id(s.task()));
        // The assignee can delete a task of their own, so the 403 below is about the creator.
        TaskSteps.delete(s.assignee(), key, id(TaskSteps.create(s.assignee(), key)));

        ApiResponse refused = TaskSteps.deleteRequest(s.assignee(), key, id(s.task())).send();

        TaskSteps.assertRefused(refused, 403, "FORBIDDEN");
        assertThat(TaskSteps.task(w.agent(), key, id(s.task()))).as("the task after the refused deletion")
                .isEqualTo(before);
    }

    // S6B-AC-12

    /** A write on a deleted task. */
    enum Write {
        TITLE,
        STATUS,
        ASSIGNEE,
        DUE_DATE,
        NOTE
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S6B-AC-12 editing a deleted task or adding a note to it is 400 TASK_DELETED and changes nothing")
    void deletedTaskTakesNoWrite(Write write) {
        Scene s = scene("CONFIRMED");
        World w = s.w();
        String key = s.issue().key();
        TaskSteps.delete(w.agent(), key, id(s.task()));
        JsonNode trace = TaskSteps.task(w.agent(), key, id(s.task()));

        ApiResponse refused = switch (write) {
            case TITLE -> TaskSteps.updateRequest(w.agent(), key, id(s.task()), body("title", "Again")).send();
            case STATUS -> TaskSteps.updateRequest(w.agent(), key, id(s.task()), body("status", "DONE")).send();
            case ASSIGNEE -> TaskSteps.updateRequest(w.agent(), key, id(s.task()),
                    body("assigneeId", w.agent().accountId())).send();
            case DUE_DATE -> TaskSteps.updateRequest(w.agent(), key, id(s.task()), body("dueDate", "2099-12-30"))
                    .send();
            case NOTE -> TaskSteps.noteRequest(w.agent(), key, id(s.task()), "Too late").send();
        };

        TaskSteps.assertRefused(refused, 400, "TASK_DELETED");
        assertThat(TaskSteps.task(w.agent(), key, id(s.task()))).as("the trace after the refused write")
                .isEqualTo(trace);
    }

    @Test
    @DisplayName("S6B-AC-12 a deleted task leaves the open count of the issue's tasks, the console list's openTaskCount, and its assignee's own tasks")
    void deletedTaskLeavesTheCounts() {
        Scene s = scene("CONFIRMED");
        World w = s.w();
        String key = s.issue().key();
        JsonNode kept = TaskSteps.create(w.agent(), key,
                body("title", TaskSteps.title("Kept"), "assigneeId", s.assignee().accountId()));
        assertThat(TaskSteps.openCount(w.agent(), key)).as("openCount before").isEqualTo(2);

        TaskSteps.delete(w.agent(), key, id(s.task()));

        assertThat(TaskSteps.openCount(w.agent(), key)).as("openCount after").isEqualTo(1);
        JsonNode item = IssueFlow.list(w.agent()).json().path("items").values().stream()
                .filter(i -> key.equals(text(i, "key"))).findFirst()
                .orElseThrow(() -> new AssertionError("Issue " + key + " is not in the console list"));
        assertThat(item.path("openTaskCount").asLong(-1)).as("openTaskCount of %s", key).isEqualTo(1);
        List<Long> mine = TaskSteps.myTasks(s.assignee(), "size", 100).json().path("items").values().stream()
                .map(TaskSteps::id).toList();
        assertThat(mine).as("the assignee's own tasks").contains(id(kept)).doesNotContain(id(s.task()));
    }
}
