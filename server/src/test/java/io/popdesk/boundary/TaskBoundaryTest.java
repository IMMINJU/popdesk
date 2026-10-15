package io.popdesk.boundary;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.task.TaskSteps.NO_TASK;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The customer-company boundary of tasks (TM-57, {@code premortem.md} 1 and 2): companies A and B
 * ({@link CompanyWorld}) and an agent assigned to A only. A task path finds its issue within what the caller may
 * see and its task and note within that issue; a refused request answers like a target nobody has and changes
 * nothing, read again afterwards. Customers reach no task path and see no task on the portal.
 */
class TaskBoundaryTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static void assertSameAnswer(ApiResponse answer, ApiResponse missing, int status, String code) {
        assertThat(missing.status()).as(missing.request() + " -> " + missing.snippet()).isEqualTo(status);
        assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(status);
        assertThat(answer.errorCode()).as("code of " + answer.request()).isEqualTo(code);
        assertThat(answer.text()).as("body of %s, like %s", answer.request(), missing.request())
                .isEqualTo(missing.text());
    }

    /** A task of an issue with a note on it, made by an admin. */
    private record Target(String key, long task, long note) {}

    private static Target target(Session staff, String key) {
        long task = id(TaskSteps.create(staff, key, body("title", TaskSteps.title("Secret task"), "dueDate",
                "2099-12-31")));
        long note = id(TaskSteps.note(staff, key, task, "Secret note " + TaskSteps.title("of " + key)));
        return new Target(key, task, note);
    }

    /** A request on a task path. */
    enum Write {
        LIST,
        CREATE,
        EDIT,
        DELETE,
        ADD_NOTE,
        EDIT_NOTE,
        DELETE_NOTE
    }

    private static ApiRequest request(Write write, Session caller, String key, Object task, Object note) {
        return switch (write) {
            case LIST -> TaskSteps.listRequest(caller, key);
            case CREATE -> TaskSteps.createRequest(caller, key, body("title", "By the caller"));
            case EDIT -> TaskSteps.updateRequest(caller, key, task, body("title", "By the caller", "status", "DONE"));
            case DELETE -> TaskSteps.deleteRequest(caller, key, task);
            case ADD_NOTE -> TaskSteps.noteRequest(caller, key, task, "By the caller");
            case EDIT_NOTE -> TaskSteps.editNoteRequest(caller, key, task, note, "By the caller");
            case DELETE_NOTE -> TaskSteps.deleteNoteRequest(caller, key, task, note);
        };
    }

    // S6B-AC-40

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S6B-AC-40 an agent assigned to A only, and an agent assigned to nothing, get the 404 of an issue key nobody has for every task path of B's issue, and B's tasks stay as they were")
    void agentOfACannotReachTasksOfB(Write write) {
        CompanyWorld w = CompanyWorld.build(fixtures);
        Target ofB = target(fixtures.admin(w.admin()), w.b().publicIssue().key());
        JsonNode before = TaskSteps.list(w.admin(), ofB.key());

        for (Session caller : List.of(w.agentOfA(), fixtures.agent(w.admin()))) {
            assertSameAnswer(request(write, caller, ofB.key(), ofB.task(), ofB.note()).send(),
                    request(write, caller, NO_KEY, ofB.task(), ofB.note()).send(), 404, "NOT_FOUND");
        }

        assertThat(TaskSteps.list(w.admin(), ofB.key())).as("B's tasks after the requests").isEqualTo(before);
        assertThat(TaskSteps.list(w.agentOfB(), ofB.key())).as("B's tasks for the agent of B").isEqualTo(before);
    }

    // S6B-AC-41

    /** Where the task in the path comes from, under the path of A's public issue. */
    enum Elsewhere {
        TASK_OF_B,
        TASK_OF_ANOTHER_ISSUE_OF_A
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Elsewhere.class)
    @DisplayName("S6B-AC-41 under the path of A's issue, a task of B or of another issue of A is the 404 of a task ID nobody has for editing, deleting, adding a note, and editing and deleting its note, for the agent of A and an admin, also as a non-numeric ID, and that task stays as it was")
    void taskOfAnotherIssueIsNotFound(Elsewhere elsewhere) {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String key = w.a().publicIssue().key();
        target(w.agentOfA(), key);
        Target other = elsewhere == Elsewhere.TASK_OF_B
                ? target(fixtures.admin(w.admin()), w.b().publicIssue().key())
                : target(w.agentOfA(), w.a().privateIssue().key());
        JsonNode ownBefore = TaskSteps.list(w.admin(), key);
        JsonNode otherBefore = TaskSteps.list(w.admin(), other.key());

        for (Session caller : List.of(w.agentOfA(), fixtures.admin(w.admin()))) {
            for (Write write : List.of(Write.EDIT, Write.DELETE, Write.ADD_NOTE, Write.EDIT_NOTE,
                    Write.DELETE_NOTE)) {
                ApiResponse missing = request(write, caller, key, NO_TASK, other.note()).send();
                assertSameAnswer(request(write, caller, key, other.task(), other.note()).send(), missing, 404,
                        "NOT_FOUND");
                assertSameAnswer(request(write, caller, key, "x" + other.task(), other.note()).send(), missing, 404,
                        "NOT_FOUND");
            }
        }

        assertThat(TaskSteps.list(w.admin(), other.key())).as("the other task after the requests")
                .isEqualTo(otherBefore);
        assertThat(TaskSteps.list(w.admin(), key)).as("A's tasks after the requests").isEqualTo(ownBefore);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Elsewhere.class)
    @DisplayName("S6B-AC-41 under the path of a task of A, a note of a task of B or of another task of A is the 404 of a note ID nobody has for editing and deleting, for its author, and the note stays as it was")
    void noteOfAnotherTaskIsNotFound(Elsewhere elsewhere) {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String key = w.a().publicIssue().key();
        Session admin = fixtures.admin(w.admin());
        Target own = target(admin, key);
        Target other = elsewhere == Elsewhere.TASK_OF_B
                ? target(admin, w.b().publicIssue().key())
                : target(admin, key);
        JsonNode ownBefore = TaskSteps.list(w.admin(), key);
        JsonNode otherBefore = TaskSteps.list(w.admin(), other.key());

        for (Write write : List.of(Write.EDIT_NOTE, Write.DELETE_NOTE)) {
            ApiResponse missing = request(write, admin, key, own.task(), NO_TASK).send();
            assertSameAnswer(request(write, admin, key, own.task(), other.note()).send(), missing, 404, "NOT_FOUND");
            assertSameAnswer(request(write, admin, key, own.task(), "x" + other.note()).send(), missing, 404,
                    "NOT_FOUND");
        }

        assertThat(TaskSteps.list(w.admin(), other.key())).as("the other note after the requests")
                .isEqualTo(otherBefore);
        assertThat(TaskSteps.list(w.admin(), key)).as("A's tasks after the requests").isEqualTo(ownBefore);
    }

    // S6B-AC-42

    @Test
    @DisplayName("S6B-AC-42 a customer calling the task paths and their own tasks gets what a console path already gives a customer, on the portal host and with the session carried to the console host, and nothing changes")
    void customerGetsTheConsoleAnswer() {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String key = w.a().publicIssue().key();
        Target ofA = target(w.agentOfA(), key);
        JsonNode before = TaskSteps.list(w.admin(), key);
        Session customer = w.a().reporter();
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);

        for (ApiClient client : List.of(customer.client(), carried)) {
            ApiResponse existing = client.get("/api/console/issues/" + key).send();
            List<ApiResponse> answers = List.of(
                    client.get(TaskSteps.tasksPath(key)).send(),
                    client.post(TaskSteps.tasksPath(key)).json(Map.of("title", "By customer")).send(),
                    client.patch(TaskSteps.taskPath(key, ofA.task())).json(Map.of("title", "By customer")).send(),
                    client.delete(TaskSteps.taskPath(key, ofA.task())).send(),
                    client.post(TaskSteps.notesPath(key, ofA.task())).json(Map.of("content", "By customer")).send(),
                    client.patch(TaskSteps.notePath(key, ofA.task(), ofA.note())).json(Map.of("content", "By customer"))
                            .send(),
                    client.delete(TaskSteps.notePath(key, ofA.task(), ofA.note())).send(),
                    client.get(TaskSteps.MY_TASKS).send());

            assertThat(existing.status()).as(existing.request() + " -> " + existing.snippet()).isIn(401, 404);
            for (ApiResponse answer : answers) {
                assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet())
                        .isEqualTo(existing.status());
                assertThat(answer.errorCode()).as("code of " + answer.request()).isEqualTo(existing.errorCode());
            }
        }
        assertThat(TaskSteps.list(w.admin(), key)).as("A's tasks after the customer's requests").isEqualTo(before);
    }

    @Test
    @DisplayName("S6B-AC-42 the portal's issue detail, issue list, and inbox of the reporter hold no task, no task title, and no task count, while the console has the task")
    void portalShowsNoTask() {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String key = w.a().publicIssue().key();
        String word = IssueFlow.word();
        long task = id(TaskSteps.create(w.agentOfA(), key,
                body("title", "Refund " + word, "assigneeId", w.agentOfA().accountId())));
        TaskSteps.create(fixtures.admin(w.admin()), key,
                body("title", "Ask " + word, "assigneeId", w.agentOfA().accountId()));
        Comment later = fixtures.comment(w.agentOfA(), key, "A later answer");
        Session reporter = w.a().reporter();
        Wait.until("the reporter's inbox item about the later answer", () -> TalkFlow.inboxOf(reporter, key).stream()
                .filter(n -> n.path("commentId").asLong(-1) == later.id()).findFirst().orElse(null));

        List<ApiResponse> portal = List.of(
                reporter.client().get("/api/portal/issues/" + key).expect(200),
                IssueFlow.list(reporter),
                reporter.client().get("/api/portal/issues/" + key + "/comments").query("size", 100).expect(200),
                reporter.client().get("/api/portal/notifications").query("size", 100).expect(200));

        assertThat(TaskSteps.task(w.agentOfA(), key, task).path("title").asString("")).contains(word);
        assertThat(IssueFlow.list(w.agentOfA()).text()).as("the console list").contains("openTaskCount");
        for (ApiResponse answer : portal) {
            assertThat(answer.text()).as("answer to " + answer.request()).doesNotContain(word)
                    .doesNotContainIgnoringCase("task");
        }
    }
}
