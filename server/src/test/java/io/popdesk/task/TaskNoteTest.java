package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.entry;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.personId;
import static io.popdesk.task.TaskSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The notes of a task (S6B-AC-20 to 22, TASK-3) and the task list that gives tasks, notes, and changes in one
 * answer (S6B-AC-24, TASK-4).
 */
class TaskNoteTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    // S6B-AC-20

    @Test
    @DisplayName("S6B-AC-20 an agent adds a note of 1 character and an admin one of 4,000 with line breaks: 201, kind NOTE, the content as written, the author, the time, never edited, not deleted, and the task lists both")
    void addsNotes() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        String long4000 = "line one\nline two\n" + "x".repeat(4000 - 18);
        Instant at = clock.advance(Duration.ofMinutes(1));

        JsonNode shortNote = TaskSteps.note(w.agent(), issue.key(), task, "a");
        JsonNode longNote = TaskSteps.note(admin, issue.key(), task, long4000);

        assertThat(long4000).hasSize(4000);
        assertThat(text(shortNote, "kind")).isEqualTo("NOTE");
        assertThat(text(shortNote, "content")).isEqualTo("a");
        assertThat(personId(shortNote, "author")).isEqualTo(w.agent().accountId());
        assertThat(IssueFlow.time(shortNote, "createdAt")).isEqualTo(at);
        assertThat(shortNote.path("editedAt").isNull()).as("editedAt of %s", shortNote).isTrue();
        assertThat(shortNote.path("deleted").asBoolean(true)).as("deleted").isFalse();
        assertThat(text(longNote, "content")).as("content with line breaks").isEqualTo(long4000);
        assertThat(personId(longNote, "author")).isEqualTo(admin.accountId());
        assertThat(TaskSteps.notes(TaskSteps.task(w.agent(), issue.key(), task), "NOTE"))
                .as("notes of the task read again").containsExactly(shortNote, longNote);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"empty", "blank", "4001"})
    @DisplayName("S6B-AC-20 an empty note, one of whitespace only, and one of 4,001 characters are 400 INVALID_INPUT content, and the task keeps no note")
    void wrongNotesAreRefused(String kind) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        String content = switch (kind) {
            case "empty" -> "";
            case "blank" -> " \n\t ";
            default -> "x".repeat(4001);
        };

        ApiResponse refused = TaskSteps.noteRequest(w.agent(), issue.key(), task, content).send();

        TaskSteps.assertRefused(refused, 400, "INVALID_INPUT");
        assertThat(TaskSteps.fieldCode(refused, "content")).as("code of content in " + refused.snippet()).isNotNull();
        assertThat(TaskSteps.notes(TaskSteps.task(w.agent(), issue.key(), task))).as("entries of the task")
                .isEmpty();
    }

    // S6B-AC-21

    @Test
    @DisplayName("S6B-AC-21 the author edits a note: 200, the new content, and editedAt; the edit is checked like a new note")
    void authorEdits() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        JsonNode note = TaskSteps.note(w.agent(), issue.key(), task, "First");
        Instant editedAt = clock.advance(Duration.ofMinutes(3));

        JsonNode edited = TaskSteps.editNote(w.agent(), issue.key(), task, id(note), "Second");
        ApiResponse blank = TaskSteps.editNoteRequest(w.agent(), issue.key(), task, id(note), "  ").send();

        assertThat(text(edited, "content")).isEqualTo("Second");
        assertThat(IssueFlow.time(edited, "editedAt")).as("editedAt").isEqualTo(editedAt);
        assertThat(edited.path("createdAt")).as("createdAt").isEqualTo(note.path("createdAt"));
        TaskSteps.assertRefused(blank, 400, "INVALID_INPUT");
        assertThat(entry(TaskSteps.task(w.agent(), issue.key(), task), id(note))).as("the note read again")
                .isEqualTo(edited);
    }

    /** Someone who is not the author of a note. */
    enum Other {
        AGENT,
        ADMIN
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Other.class)
    @DisplayName("S6B-AC-21 another agent and an admin get 403 FORBIDDEN for editing a note they did not write, and the note stays")
    void othersMayNotEdit(Other other) {
        World w = flow.world();
        Session caller = other == Other.ADMIN ? fixtures.admin(w.admin()) : fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        JsonNode note = TaskSteps.note(w.agent(), issue.key(), task, "Stays");
        // The caller can edit a note of their own, so the 403 below is about the author.
        JsonNode own = TaskSteps.note(caller, issue.key(), task, "Mine");
        TaskSteps.editNote(caller, issue.key(), task, id(own), "Mine, edited");

        ApiResponse refused = TaskSteps.editNoteRequest(caller, issue.key(), task, id(note), "Changed").send();

        TaskSteps.assertRefused(refused, 403, "FORBIDDEN");
        assertThat(entry(TaskSteps.task(w.agent(), issue.key(), task), id(note))).as("the note read again")
                .isEqualTo(note);
    }

    // S6B-AC-22

    /** Who deletes a note an agent wrote. */
    enum Deleter {
        AUTHOR,
        ADMIN
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleter.class)
    @DisplayName("S6B-AC-22 the author or an admin deletes a note: 204, the task keeps it in its place with an empty content, who deleted it and when; deleting it again is 204 and keeps that; editing it is 400 TASK_NOTE_DELETED")
    void authorOrAdminDeletes(Deleter deleter) {
        World w = flow.world();
        Session caller = deleter == Deleter.ADMIN ? fixtures.admin(w.admin()) : w.agent();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        JsonNode first = TaskSteps.note(w.agent(), issue.key(), task, "First");
        JsonNode target = TaskSteps.note(w.agent(), issue.key(), task, "Secret " + IssueFlow.word());
        JsonNode last = TaskSteps.note(w.agent(), issue.key(), task, "Last");
        Instant deletedAt = clock.advance(Duration.ofMinutes(5));

        ApiResponse deleted = TaskSteps.deleteNoteRequest(caller, issue.key(), task, id(target)).send();
        JsonNode afterFirst = TaskSteps.task(w.agent(), issue.key(), task);
        clock.advance(Duration.ofMinutes(5));
        ApiResponse again = TaskSteps.deleteNoteRequest(caller, issue.key(), task, id(target)).send();
        ApiResponse editing = TaskSteps.editNoteRequest(w.agent(), issue.key(), task, id(target), "Back").send();

        assertThat(deleted.status()).as(deleted.request() + ": " + deleted.snippet()).isEqualTo(204);
        assertThat(deleted.body()).as("body of the 204").isEmpty();
        assertThat(TaskSteps.notes(afterFirst).stream().map(TaskSteps::id).toList()).as("entries of the task")
                .containsExactly(id(first), id(target), id(last));
        JsonNode trace = entry(afterFirst, id(target));
        assertThat(trace.path("deleted").asBoolean(false)).as("deleted of %s", trace).isTrue();
        assertThat(text(trace, "content")).as("content of the deleted note").isEmpty();
        assertThat(IssueFlow.time(trace, "deletedAt")).as("deletedAt").isEqualTo(deletedAt);
        assertThat(personId(trace, "deletedBy")).as("deletedBy").isEqualTo(caller.accountId());
        assertThat(again.status()).as("deleting again: " + again.snippet()).isEqualTo(204);
        TaskSteps.assertRefused(editing, 400, "TASK_NOTE_DELETED");
        assertThat(TaskSteps.task(w.agent(), issue.key(), task)).as("the task after deleting again and editing")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("S6B-AC-22 another agent gets 403 FORBIDDEN for deleting a note they did not write, and the note stays")
    void otherAgentsMayNotDelete() {
        World w = flow.world();
        Session other = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        JsonNode note = TaskSteps.note(w.agent(), issue.key(), task, "Stays");
        // The other agent can delete a note of their own, so the 403 below is about the author.
        TaskSteps.deleteNote(other, issue.key(), task, id(TaskSteps.note(other, issue.key(), task, "Goes")));

        ApiResponse refused = TaskSteps.deleteNoteRequest(other, issue.key(), task, id(note)).send();

        TaskSteps.assertRefused(refused, 403, "FORBIDDEN");
        assertThat(entry(TaskSteps.task(w.agent(), issue.key(), task), id(note))).as("the note read again")
                .isEqualTo(note);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Deleter.class)
    @DisplayName("S6B-AC-22 a note on a task of a CLOSED issue is deleted by its author or an admin: 204, and the issue stays CLOSED")
    void noteOfAClosedIssueIsDeleted(Deleter deleter) {
        World w = flow.world();
        Session caller = deleter == Deleter.ADMIN ? fixtures.admin(w.admin()) : w.agent();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "CONFIRMED");
        long task = id(TaskSteps.create(w.agent(), issue.key()));
        JsonNode note = TaskSteps.note(w.agent(), issue.key(), task, "Before closing");
        flow.close(w.reporter(), issue.key());

        ApiResponse deleted = TaskSteps.deleteNoteRequest(caller, issue.key(), task, id(note)).send();

        assertThat(deleted.status()).as(deleted.request() + ": " + deleted.snippet()).isEqualTo(204);
        JsonNode trace = entry(TaskSteps.task(w.agent(), issue.key(), task), id(note));
        assertThat(trace.path("deleted").asBoolean(false)).isTrue();
        assertThat(text(trace, "content")).isEmpty();
        assertThat(text(flow.detail(w.agent(), issue.key()), "status")).isEqualTo("CLOSED");
    }

    // S6B-AC-24

    @Test
    @DisplayName("S6B-AC-24 the task list gives the tasks in the order they were made, a deleted one as a trace in its place, each with its notes and changes in the order they were made, and the issue's open count")
    void listGivesEverythingInOrder() {
        World w = flow.world();
        Session other = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String key = issue.key();
        long first = id(TaskSteps.create(w.agent(), key));
        clock.advance(Duration.ofSeconds(1));
        long second = id(TaskSteps.create(other, key));
        long third = id(TaskSteps.create(w.agent(), key));
        long done = id(TaskSteps.create(w.agent(), key));
        long noteA = id(TaskSteps.note(w.agent(), key, first, "A"));
        TaskSteps.update(w.agent(), key, first, body("status", "DONE"));
        long noteB = id(TaskSteps.note(other, key, first, "B"));
        TaskSteps.update(other, key, first, body("title", "First, renamed"));
        TaskSteps.note(w.agent(), key, second, "On the second");
        TaskSteps.delete(w.agent(), key, third);
        TaskSteps.update(w.agent(), key, done, body("status", "DONE"));
        TaskSteps.update(w.agent(), key, first, body("status", "OPEN"));

        JsonNode list = TaskSteps.list(other, key);

        List<JsonNode> items = List.copyOf(list.path("items").values());
        assertThat(items.stream().map(TaskSteps::id).toList()).as("tasks").containsExactly(first, second, third, done);
        JsonNode ofFirst = items.getFirst();
        assertThat(TaskSteps.notes(ofFirst).stream().map(n -> text(n, "kind") + " " + text(n, "field")).toList())
                .as("entries of the first task, in order")
                .containsExactly("NOTE null", "CHANGE STATUS", "NOTE null", "CHANGE TITLE", "CHANGE STATUS");
        assertThat(id(TaskSteps.notes(ofFirst).get(0))).isEqualTo(noteA);
        assertThat(id(TaskSteps.notes(ofFirst).get(2))).isEqualTo(noteB);
        assertThat(TaskSteps.notes(items.get(1))).as("entries of the second task").hasSize(1);
        assertThat(items.get(2).path("deleted").asBoolean(false)).as("the third task is a trace").isTrue();
        assertThat(list.path("openCount").asLong(-1)).as("openCount: the first and the second").isEqualTo(2);
    }
}
