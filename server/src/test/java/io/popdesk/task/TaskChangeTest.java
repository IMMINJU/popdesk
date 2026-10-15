package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.changes;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.personId;
import static io.popdesk.task.TaskSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
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
 * The entries a task's changes leave (S6B-AC-30 to 32, TASK-5): one {@code CHANGE} per field that changed, with
 * who and the values before and after; none in the issue's history and no new issue version; and no edit or
 * deletion of a change.
 */
class TaskChangeTest extends BareAppTest {

    private static final String FAR_DATE = "2099-06-01";

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    // S6B-AC-30

    @Test
    @DisplayName("S6B-AC-30 creating a task with an assignee and a due date leaves no entry: the answer and the task read again have no notes")
    void creatingLeavesNoEntry() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        JsonNode task = TaskSteps.create(w.agent(), issue.key(), body("title", TaskSteps.title("New"),
                "assigneeId", w.agent().accountId(), "dueDate", FAR_DATE));

        assertThat(personId(task, "assignee")).as("assignee of the new task").isEqualTo(w.agent().accountId());
        assertThat(text(task, "dueDate")).as("dueDate of the new task").isEqualTo(FAR_DATE);
        assertThat(TaskSteps.notes(task)).as("entries of the new task").isEmpty();
        assertThat(TaskSteps.notes(TaskSteps.task(w.agent(), issue.key(), id(task))))
                .as("entries of the new task read again").isEmpty();
    }

    /** A field changed, the body that changes it, and the values the entry gives. */
    enum Change {
        STATUS(Map.of("status", "DONE"), "OPEN", "DONE"),
        DUE_DATE(body("dueDate", null), FAR_DATE, null),
        TITLE(Map.of("title", "After"), "Before", "After");

        final Map<String, Object> body;
        final String oldValue;
        final String newValue;

        Change(Map<String, Object> body, String oldValue, String newValue) {
            this.body = body;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Change.class)
    @DisplayName("S6B-AC-30 a change of the status, the due date, or the title leaves one CHANGE entry with who made it, the field, and the values before and after as text; no person")
    void changeOfAValueLeavesAnEntry(Change change) {
        World w = flow.world();
        Session editor = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", "Before", "dueDate", FAR_DATE));
        Instant at = flow.tick();

        JsonNode after = TaskSteps.update(editor, issue.key(), id(task), change.body);

        List<JsonNode> entries = changes(after);
        assertThat(entries).as("new CHANGE entries in %s", after).hasSize(1);
        JsonNode entry = entries.getFirst();
        assertThat(text(entry, "field")).isEqualTo(change.name());
        assertThat(text(entry, "oldValue")).as("oldValue").isEqualTo(change.oldValue);
        assertThat(text(entry, "newValue")).as("newValue").isEqualTo(change.newValue);
        assertThat(entry.path("oldPerson").isNull() && entry.path("newPerson").isNull()).as("people of %s", entry)
                .isTrue();
        assertThat(personId(entry, "author")).as("author").isEqualTo(editor.accountId());
        assertThat(IssueFlow.time(entry, "createdAt")).as("createdAt").isEqualTo(at);
        assertThat(text(entry, "content")).as("content of a change").isEmpty();
        assertThat(TaskSteps.task(w.agent(), issue.key(), id(task))).as("the task read again").isEqualTo(after);
    }

    @Test
    @DisplayName("S6B-AC-30 a change of the assignee leaves a CHANGE entry ASSIGNEE with the people before and after; clearing it leaves one with no person after")
    void changeOfTheAssigneeLeavesPeople() {
        World w = flow.world();
        Session first = fixtures.agent(w.admin(), w.code());
        Session second = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", TaskSteps.title("Assigned"), "assigneeId", first.accountId()));

        TaskSteps.update(w.agent(), issue.key(), id(task), body("assigneeId", second.accountId()));
        JsonNode after = TaskSteps.update(w.agent(), issue.key(), id(task), body("assigneeId", null));

        List<JsonNode> entries = changes(after);
        assertThat(entries.stream().map(e -> text(e, "field")).toList()).as("new CHANGE entries")
                .containsExactly("ASSIGNEE", "ASSIGNEE");
        assertThat(personId(entries.get(0), "oldPerson")).isEqualTo(first.accountId());
        assertThat(personId(entries.get(0), "newPerson")).isEqualTo(second.accountId());
        assertThat(text(entries.get(0).path("newPerson"), "name")).isEqualTo(second.name());
        assertThat(personId(entries.get(1), "oldPerson")).isEqualTo(second.accountId());
        assertThat(personId(entries.get(1), "newPerson")).as("newPerson after clearing").isNull();
    }

    @Test
    @DisplayName("S6B-AC-30 one edit of two fields leaves two entries; an edit that sends the values the task has leaves none")
    void onlyChangedFieldsLeaveEntries() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", "Same", "assigneeId", w.agent().accountId(), "dueDate", FAR_DATE));

        JsonNode same = TaskSteps.update(w.agent(), issue.key(), id(task), body("title", "Same", "status", "OPEN",
                "assigneeId", w.agent().accountId(), "dueDate", FAR_DATE));
        JsonNode two = TaskSteps.update(w.agent(), issue.key(), id(task), body("title", "Other", "status", "DONE",
                "dueDate", FAR_DATE));

        assertThat(changes(same)).as("entries after sending the same values").isEmpty();
        assertThat(changes(two).stream().map(e -> text(e, "field")).toList())
                .as("entries after changing the title and the status")
                .containsExactlyInAnyOrder("TITLE", "STATUS");
    }

    // S6B-AC-31

    @Test
    @DisplayName("S6B-AC-31 creating, editing, and deleting tasks and notes leave the issue's history, version, and detail as they were")
    void taskWorkLeavesTheIssueAlone() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String key = issue.key();
        JsonNode detail = flow.detail(w.agent(), key);
        List<JsonNode> history = flow.history(w.agent(), key);
        flow.tick();

        long task = id(TaskSteps.create(w.agent(), key, body("title", TaskSteps.title("Quiet"), "assigneeId",
                w.agent().accountId(), "dueDate", FAR_DATE)));
        TaskSteps.update(w.agent(), key, task, body("status", "DONE", "title", "Renamed", "dueDate", null,
                "assigneeId", null));
        long note = id(TaskSteps.note(w.agent(), key, task, "A note"));
        TaskSteps.editNote(w.agent(), key, task, note, "Edited");
        TaskSteps.deleteNote(w.agent(), key, task, note);
        TaskSteps.delete(w.agent(), key, task);

        assertThat(flow.history(w.agent(), key)).as("history of the issue").isEqualTo(history);
        assertThat(flow.detail(w.agent(), key)).as("the issue, with its version and updatedAt").isEqualTo(detail);
    }

    // S6B-AC-32

    @Test
    @DisplayName("S6B-AC-32 editing or deleting a CHANGE entry is 403 FORBIDDEN for the one who made the change and for an admin, and the entry stays")
    void changesAreNotEditedOrDeleted() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        JsonNode after = TaskSteps.update(w.agent(), issue.key(), task, body("status", "DONE"));
        JsonNode change = changes(after).getLast();

        for (Session caller : List.of(w.agent(), admin)) {
            ApiResponse editing = TaskSteps.editNoteRequest(caller, issue.key(), task, id(change), "Rewritten")
                    .send();
            ApiResponse deleting = TaskSteps.deleteNoteRequest(caller, issue.key(), task, id(change)).send();

            TaskSteps.assertRefused(editing, 403, "FORBIDDEN");
            TaskSteps.assertRefused(deleting, 403, "FORBIDDEN");
        }
        assertThat(TaskSteps.task(w.agent(), issue.key(), task)).as("the task after the refused requests")
                .isEqualTo(after);
    }
}
