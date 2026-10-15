package io.popdesk.task;

import static io.popdesk.task.TaskSteps.assertInvalid;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.personId;
import static io.popdesk.task.TaskSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.due.DueSteps;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Creating and editing a task (S6B-AC-01 to 03, 05 to 07): the fields of a new task, the title's limits, editing
 * only the fields sent, the due date by the rules of the issue's due date, who and when a task was done, and
 * {@code overdue}. Each test makes its own issue.
 */
class TaskEditTest extends BareAppTest {

    private IssueFlow flow;
    private DueSteps due;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        due = new DueSteps(fixtures, clock);
    }

    private LocalDate today() {
        return due.today(DueSteps.UTC);
    }

    // S6B-AC-01

    @Test
    @DisplayName("S6B-AC-01 an agent creates a task with a title only and an admin one with an assignee and a due date: 201, OPEN, the fields given, who created it and when, nothing done, and the list has both in that order")
    void createsTasks() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        LocalDate dueDate = today().plusDays(3);
        Instant at = clock.advance(Duration.ofMinutes(1));
        String plain = TaskSteps.title("Call back");
        String full = TaskSteps.title("Send the invoice");

        ApiResponse first = TaskSteps.createRequest(w.agent(), issue.key(), body("title", plain)).send();
        JsonNode second = TaskSteps.create(admin, issue.key(),
                body("title", full, "assigneeId", w.agent().accountId(), "dueDate", dueDate.toString()));

        assertThat(first.status()).as(first.request() + ": " + first.snippet()).isEqualTo(201);
        JsonNode created = first.json();
        assertThat(text(created, "title")).isEqualTo(plain);
        assertThat(text(created, "status")).isEqualTo("OPEN");
        assertThat(created.path("assignee").isNull()).as("assignee of %s", created).isTrue();
        assertThat(created.path("dueDate").isNull()).as("dueDate of %s", created).isTrue();
        assertThat(created.path("overdue").asBoolean(true)).as("overdue").isFalse();
        assertThat(personId(created, "createdBy")).isEqualTo(w.agent().accountId());
        assertThat(IssueFlow.time(created, "createdAt")).isEqualTo(at);
        assertThat(created.path("doneAt").isNull() && created.path("doneBy").isNull()).as("done of %s", created)
                .isTrue();
        assertThat(created.path("deleted").asBoolean(true)).as("deleted").isFalse();
        assertThat(TaskSteps.notes(created)).as("notes of a new task").isEmpty();
        assertThat(personId(second, "assignee")).isEqualTo(w.agent().accountId());
        assertThat(text(second, "dueDate")).isEqualTo(dueDate.toString());
        assertThat(personId(second, "createdBy")).isEqualTo(admin.accountId());
        List<JsonNode> listed = TaskSteps.tasks(w.agent(), issue.key());
        assertThat(listed.stream().map(TaskSteps::id).toList()).as("tasks of the issue")
                .containsExactly(id(created), id(second));
        assertThat(listed.getFirst()).as("the first task in the list").isEqualTo(created);
        assertThat(TaskSteps.openCount(w.agent(), issue.key())).as("openCount").isEqualTo(2);
    }

    // S6B-AC-02

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "   ", "\n\t"})
    @DisplayName("S6B-AC-02 a blank title is 400 INVALID_INPUT for creating and for editing, and changes nothing")
    void blankTitleIsRefused(String title) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key());

        ApiResponse creating = TaskSteps.createRequest(w.agent(), issue.key(), body("title", title)).send();
        ApiResponse editing = TaskSteps.updateRequest(w.agent(), issue.key(), id(task), body("title", title)).send();

        for (ApiResponse answer : List.of(creating, editing)) {
            TaskSteps.assertRefused(answer, 400, "INVALID_INPUT");
            assertThat(TaskSteps.fieldCode(answer, "title")).as("code of title in " + answer.snippet()).isNotNull();
        }
        assertThat(TaskSteps.tasks(w.agent(), issue.key())).as("tasks after the refused requests")
                .containsExactly(task);
    }

    @Test
    @DisplayName("S6B-AC-02 a title of 100 characters after trimming is taken, and one of 101 is 400 INVALID_INPUT title TOO_LONG for creating and for editing")
    void titleOfMoreThan100IsRefused() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String hundred = "가".repeat(100);

        JsonNode taken = TaskSteps.create(w.agent(), issue.key(), "  " + hundred + "  ");
        ApiResponse creating = TaskSteps.createRequest(w.agent(), issue.key(), body("title", hundred + "a")).send();
        ApiResponse editing = TaskSteps.updateRequest(w.agent(), issue.key(), id(taken),
                body("title", hundred + "a")).send();

        assertThat(text(taken, "title")).as("the title stored, trimmed").isEqualTo(hundred);
        assertInvalid(creating, "title", "TOO_LONG");
        assertInvalid(editing, "title", "TOO_LONG");
        assertThat(TaskSteps.tasks(w.agent(), issue.key())).as("tasks after the refused requests")
                .containsExactly(taken);
    }

    // S6B-AC-03

    static List<Arguments> edits() {
        return List.of(
                Arguments.of("title", Map.of("title", "New title")),
                Arguments.of("status", Map.of("status", "DONE")),
                Arguments.of("assigneeId", body("assigneeId", null)),
                Arguments.of("dueDate", body("dueDate", null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edits")
    @DisplayName("S6B-AC-03 an edit with one field changes that field only; null clears the assignee and the due date")
    void editChangesOnlyTheFieldSent(String field, Map<String, Object> change) {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String title = TaskSteps.title("Before");
        LocalDate dueDate = today().plusDays(5);
        JsonNode before = TaskSteps.create(w.agent(), issue.key(),
                body("title", title, "assigneeId", w.agent().accountId(), "dueDate", dueDate.toString()));

        JsonNode after = TaskSteps.update(admin, issue.key(), id(before), change);

        assertThat(text(after, "title")).as("title").isEqualTo(field.equals("title") ? "New title" : title);
        assertThat(text(after, "status")).as("status").isEqualTo(field.equals("status") ? "DONE" : "OPEN");
        assertThat(personId(after, "assignee")).as("assignee")
                .isEqualTo(field.equals("assigneeId") ? null : w.agent().accountId());
        assertThat(text(after, "dueDate")).as("dueDate")
                .isEqualTo(field.equals("dueDate") ? null : dueDate.toString());
        assertThat(TaskSteps.task(w.agent(), issue.key(), id(before))).as("the task read again").isEqualTo(after);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"title", "status"})
    @DisplayName("S6B-AC-03 null for the title or the status is 400 INVALID_INPUT and changes nothing")
    void nullTitleOrStatusIsRefused(String field) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode before = TaskSteps.create(w.agent(), issue.key());

        ApiResponse answer = TaskSteps.updateRequest(w.agent(), issue.key(), id(before), body(field, null)).send();

        TaskSteps.assertRefused(answer, 400, "INVALID_INPUT");
        assertThat(TaskSteps.fieldCode(answer, field)).as("code of %s in %s", field, answer.snippet()).isNotNull();
        assertThat(TaskSteps.task(w.agent(), issue.key(), id(before))).as("the task read again").isEqualTo(before);
    }

    // S6B-AC-05

    static List<Arguments> wrongDates() {
        return List.of(
                Arguments.of("yesterday", "OUT_OF_RANGE"),
                Arguments.of("2026-02-30", "INVALID_FORMAT"),
                Arguments.of("2099-12-31T09:00:00", "INVALID_FORMAT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongDates")
    @DisplayName("S6B-AC-05 a due date before today or not YYYY-MM-DD is the 400 of the issue's due date, for creating and for editing, and changes nothing")
    void wrongDueDateIsRefused(String value, String code) {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        String date = value.equals("yesterday") ? today().minusDays(1).toString() : value;
        JsonNode before = TaskSteps.create(w.agent(), issue.key());
        ApiResponse ofIssue = DueSteps.setRequest(w.agent(), issue.key(), date).send();

        ApiResponse creating = TaskSteps.createRequest(w.agent(), issue.key(),
                body("title", TaskSteps.title("Dated"), "dueDate", date)).send();
        ApiResponse editing = TaskSteps.updateRequest(w.agent(), issue.key(), id(before), body("dueDate", date))
                .send();

        assertInvalid(ofIssue, "dueDate", code);
        assertInvalid(creating, "dueDate", code);
        assertInvalid(editing, "dueDate", code);
        assertThat(TaskSteps.tasks(w.agent(), issue.key())).as("tasks after the refused requests")
                .containsExactly(before);
    }

    @Test
    @DisplayName("S6B-AC-05, S6B-AC-07 today is taken; once it has passed the task is overdue, the same date sent again is 400 OUT_OF_RANGE, and the title is still edited without a due date in the body")
    void passedDueDate() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        LocalDate today = today();
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", TaskSteps.title("Today"), "dueDate", today.toString()));
        assertThat(task.path("overdue").asBoolean(true)).as("overdue on the day").isFalse();
        clock.advance(Duration.ofDays(1));
        // A session ends 8 hours after sign-in on the app's clock (AUTH-4).
        Session agent = fixtures.signInAgain(w.agent());

        ApiResponse again = TaskSteps.updateRequest(agent, issue.key(), id(task),
                body("dueDate", today.toString())).send();
        JsonNode renamed = TaskSteps.update(agent, issue.key(), id(task), body("title", "Renamed"));

        assertInvalid(again, "dueDate", "OUT_OF_RANGE");
        assertThat(text(renamed, "dueDate")).as("dueDate after renaming").isEqualTo(today.toString());
        assertThat(renamed.path("overdue").asBoolean(false)).as("overdue the day after").isTrue();
        assertThat(text(renamed, "title")).isEqualTo("Renamed");
    }

    // S6B-AC-06, S6B-AC-07

    @Test
    @DisplayName("S6B-AC-06, S6B-AC-07 DONE records who and when and ends overdue; DONE again changes nothing; OPEN clears both and is overdue again")
    void doneAndOpenAgain() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", TaskSteps.title("Overdue"), "dueDate", today().toString()));
        clock.advance(Duration.ofDays(2));
        // A session ends 8 hours after sign-in on the app's clock (AUTH-4): sign in after moving it.
        Session admin = fixtures.admin(w.admin());
        Session agent = fixtures.signInAgain(w.agent());
        assertThat(TaskSteps.task(agent, issue.key(), id(task)).path("overdue").asBoolean(false))
                .as("overdue of the OPEN task after its date").isTrue();
        Instant doneAt = clock.advance(Duration.ofMinutes(1));

        JsonNode done = TaskSteps.update(admin, issue.key(), id(task), body("status", "DONE"));
        clock.advance(Duration.ofMinutes(1));
        JsonNode doneAgain = TaskSteps.update(agent, issue.key(), id(task), body("status", "DONE"));
        JsonNode reopened = TaskSteps.update(agent, issue.key(), id(task), body("status", "OPEN"));

        assertThat(text(done, "status")).isEqualTo("DONE");
        assertThat(IssueFlow.time(done, "doneAt")).as("doneAt").isEqualTo(doneAt);
        assertThat(personId(done, "doneBy")).as("doneBy").isEqualTo(admin.accountId());
        assertThat(done.path("overdue").asBoolean(true)).as("overdue of the DONE task").isFalse();
        assertThat(doneAgain).as("the task after DONE again").isEqualTo(done);
        assertThat(text(reopened, "status")).isEqualTo("OPEN");
        assertThat(reopened.path("doneAt").isNull()).as("doneAt after OPEN: %s", reopened).isTrue();
        assertThat(reopened.path("doneBy").isNull()).as("doneBy after OPEN: %s", reopened).isTrue();
        assertThat(reopened.path("overdue").asBoolean(false)).as("overdue after OPEN").isTrue();
    }
}
