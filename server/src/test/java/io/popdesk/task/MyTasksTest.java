package io.popdesk.task;

import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.text;
import static io.popdesk.task.TaskSteps.title;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.due.DueSteps;
import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The caller's own tasks, {@code GET /api/console/tasks} (slice 6b, C-15, S6B-AC-60 to 63): what an item
 * holds, the two orders and the pages, the {@code status} filter, and what is left out. Each test makes a new
 * agent, so the list holds only the tasks the test gave them. Task writes are done by a new admin of the test.
 */
class MyTasksTest extends BareAppTest {

    private IssueFlow flow;
    private DueSteps due;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        due = new DueSteps(fixtures, clock);
    }

    /** An admin, companies A and B with a customer each, and an agent assigned to both. */
    private record Scene(Session admin, Company a, Company b, Session customerA, Session customerB, Session agent) {}

    private Scene scene() {
        Session admin = fixtures.admin(flow.admin());
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        return new Scene(admin, a, b, fixtures.customer(admin, a.code()), fixtures.customer(admin, b.code()),
                fixtures.agent(admin, a.code(), b.code()));
    }

    private LocalDate today() {
        return due.today(DueSteps.UTC);
    }

    /** A task of the issue the admin gives to {@code assignee}, with a due date or none. */
    private long taskFor(Scene s, String key, Session assignee, LocalDate dueDate) {
        return id(TaskSteps.create(s.admin(), key, body("title", title("Mine"), "assigneeId", assignee.accountId(),
                "dueDate", dueDate == null ? null : dueDate.toString())));
    }

    /** Every item of the caller's tasks with this status (null: left out), page by page of {@code size}. */
    private static List<JsonNode> all(Session staff, String status, int size) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 50; page++) {
            ApiResponse response = status == null ? TaskSteps.myTasks(staff, "page", page, "size", size)
                    : TaskSteps.myTasks(staff, "status", status, "page", page, "size", size);
            out.addAll(response.json().path("items").values());
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("More than 50 pages of my tasks");
    }

    private static List<Long> ids(List<JsonNode> items) {
        return items.stream().map(TaskSteps::id).toList();
    }

    private static List<Long> open(Session staff) {
        return ids(all(staff, "OPEN", 100));
    }

    private static List<Long> done(Session staff) {
        return ids(all(staff, "DONE", 100));
    }

    // S6B-AC-60

    @Test
    @DisplayName("S6B-AC-60 my tasks gives the caller's tasks of every issue and company, each with the task's "
            + "ID, title, status, due date, overdue, and done time, the issue's key, title, and status, and the "
            + "company's code and name, in a page with page, size, totalElements, and totalPages; others' tasks "
            + "and tasks without assignee are not there")
    void givesTheCallersTasksOfEveryIssue() {
        Scene s = scene();
        Issue ofA = fixtures.fileIssue(s.customerA(), s.a().code());
        Issue ofB = fixtures.fileIssue(s.customerB(), s.b().code());
        Session other = fixtures.agent(s.admin(), s.a().code());
        LocalDate dueDate = today().plusDays(2);
        long inA = taskFor(s, ofA.key(), s.agent(), dueDate);
        long inB = taskFor(s, ofB.key(), s.agent(), null);
        taskFor(s, ofA.key(), other, null);
        TaskSteps.create(s.admin(), ofA.key());
        flow.changeStatus(s.admin(), ofB.key(), "IN_PROGRESS");

        ApiResponse page = TaskSteps.myTasks(s.agent());

        List<JsonNode> items = new ArrayList<>(page.json().path("items").values());
        assertThat(ids(items)).as("tasks of the agent, the one with a due date first").containsExactly(inA, inB);
        assertThat(page.jsonLong("page")).as("page").isEqualTo(1);
        assertThat(page.jsonLong("size")).as("size").isEqualTo(20);
        assertThat(page.jsonLong("totalElements")).as("totalElements").isEqualTo(2);
        assertThat(page.jsonLong("totalPages")).as("totalPages").isEqualTo(1);
        JsonNode a = items.get(0);
        JsonNode b = items.get(1);
        for (JsonNode item : items) {
            assertThat(new TreeSet<>(item.propertyNames())).as("fields of %s", item).isEqualTo(new TreeSet<>(Set.of(
                    "id", "title", "status", "dueDate", "overdue", "doneAt", "issue", "companyCode", "companyName")));
            assertThat(new TreeSet<>(item.path("issue").propertyNames())).as("fields of issue in %s", item)
                    .isEqualTo(new TreeSet<>(Set.of("key", "title", "status")));
            assertThat(text(item, "status")).as("status of %s", item).isEqualTo("OPEN");
            assertThat(item.path("overdue").isBoolean() && !item.path("overdue").asBoolean())
                    .as("overdue is false in %s", item).isTrue();
            assertThat(item.path("doneAt").isNull()).as("doneAt is null in %s", item).isTrue();
        }
        assertThat(text(a, "title")).as("title").isEqualTo(TaskSteps.task(s.admin(), ofA.key(), inA)
                .path("title").asString(null));
        assertThat(text(a, "dueDate")).as("dueDate").isEqualTo(dueDate.toString());
        assertThat(b.path("dueDate").isNull()).as("dueDate is null in %s", b).isTrue();
        assertThat(text(a.path("issue"), "key") + " " + text(b.path("issue"), "key")).as("issue keys")
                .isEqualTo(ofA.key() + " " + ofB.key());
        assertThat(text(a.path("issue"), "title")).as("issue title")
                .isEqualTo(ofA.json().path("title").asString(null));
        assertThat(text(a.path("issue"), "status") + " " + text(b.path("issue"), "status")).as("issue statuses")
                .isEqualTo("RECEIVED IN_PROGRESS");
        assertThat(List.of(text(a, "companyCode"), text(a, "companyName"), text(b, "companyCode"),
                text(b, "companyName"))).as("company codes and names")
                .containsExactly(s.a().code(), s.a().name(), s.b().code(), s.b().name());
    }

    @Test
    @DisplayName("S6B-AC-60 an OPEN task whose due date has passed is overdue in my tasks")
    void passedDueDateIsOverdue() {
        Scene s = scene();
        Issue issue = fixtures.fileIssue(s.customerA(), s.a().code());
        long task = taskFor(s, issue.key(), s.agent(), today());

        clock.advance(Duration.ofDays(2));
        // A session ends 8 hours after sign-in on the app's clock (AUTH-4).
        Session agent = fixtures.signInAgain(s.agent());

        JsonNode item = TaskSteps.myTasks(agent).json().path("items").path(0);
        assertThat(id(item)).as("the agent's task").isEqualTo(task);
        assertThat(item.path("overdue").asBoolean(false)).as("overdue of %s", item).isTrue();
    }

    // S6B-AC-61

    @Test
    @DisplayName("S6B-AC-61 OPEN tasks come by due date, earliest first and those without one last, then in the "
            + "order they were made; pages of 2 hold each task once")
    void openTasksComeByDueDate() {
        Scene s = scene();
        String key = fixtures.fileIssue(s.customerA(), s.a().code()).key();
        LocalDate later = today().plusDays(3);
        LocalDate sooner = today().plusDays(1);
        long t1 = taskFor(s, key, s.agent(), later);
        long t2 = taskFor(s, key, s.agent(), null);
        long t3 = taskFor(s, key, s.agent(), sooner);
        long t4 = taskFor(s, key, s.agent(), later);
        long t5 = taskFor(s, key, s.agent(), null);
        long t6 = taskFor(s, key, s.agent(), sooner);
        List<Long> expected = List.of(t3, t6, t1, t4, t2, t5);

        assertThat(ids(all(s.agent(), null, 100))).as("tasks without status, in one page").isEqualTo(expected);
        assertThat(ids(all(s.agent(), "OPEN", 2))).as("OPEN tasks, pages of 2 one after another")
                .isEqualTo(expected);
        assertThat(TaskSteps.myTasks(s.agent(), "size", 2, "page", 2).jsonLong("totalPages")).as("totalPages")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("S6B-AC-61 DONE tasks come by the time they were done, latest first; pages of 2 hold each task "
            + "once; OPEN tasks are not among them")
    void doneTasksComeByDoneTime() {
        Scene s = scene();
        String key = fixtures.fileIssue(s.customerA(), s.a().code()).key();
        long t1 = taskFor(s, key, s.agent(), null);
        long t2 = taskFor(s, key, s.agent(), today().plusDays(1));
        long t3 = taskFor(s, key, s.agent(), null);
        long stillOpen = taskFor(s, key, s.agent(), null);
        for (long task : List.of(t2, t1, t3)) {
            flow.tick();
            TaskSteps.update(s.admin(), key, task, body("status", "DONE"));
        }

        assertThat(ids(all(s.agent(), "DONE", 2))).as("DONE tasks, pages of 2 one after another")
                .containsExactly(t3, t1, t2);
        assertThat(open(s.agent())).as("OPEN tasks").containsExactly(stillOpen);
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(strings = {"CLOSED", "open", "ALL", "OPEN,DONE"})
    @DisplayName("S6B-AC-61 a status other than OPEN and DONE is 400 INVALID_INPUT with status UNKNOWN_VALUE")
    void otherStatusIsRefused(String status) {
        Scene s = scene();

        ApiResponse r = TaskSteps.myTasksRequest(s.agent(), "status", status).send();

        TaskSteps.assertInvalid(r, "status", "UNKNOWN_VALUE");
    }

    @ParameterizedTest(name = "status {0} and {0}")
    @ValueSource(strings = {"OPEN", "DONE"})
    @DisplayName("S6B-AC-61 status given twice is 400 INVALID_INPUT with status UNKNOWN_VALUE, also with one "
            + "value twice and with OPEN and DONE")
    void statusTwiceIsRefused(String first) {
        Scene s = scene();

        ApiResponse same = TaskSteps.myTasksRequest(s.agent(), "status", first, "status", first).send();
        ApiResponse both = TaskSteps.myTasksRequest(s.agent(), "status", first, "status",
                first.equals("OPEN") ? "DONE" : "OPEN").send();

        TaskSteps.assertInvalid(same, "status", "UNKNOWN_VALUE");
        TaskSteps.assertInvalid(both, "status", "UNKNOWN_VALUE");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"assignee", "sort", "issue"})
    @DisplayName("S6B-AC-61 a query parameter my tasks does not take is 400 INVALID_INPUT with UNKNOWN_PARAMETER "
            + "for it")
    void unknownParameterIsRefused(String name) {
        Scene s = scene();

        ApiResponse r = TaskSteps.myTasksRequest(s.agent(), name, "x").send();

        TaskSteps.assertInvalid(r, name, "UNKNOWN_PARAMETER");
    }

    // S6B-AC-62

    @Test
    @DisplayName("S6B-AC-62 my tasks leaves out the tasks of CLOSED issues, OPEN or DONE, and deleted tasks, and "
            + "keeps those of a RESOLVED issue")
    void leavesOutClosedIssuesAndDeletedTasks() {
        Scene s = scene();
        String resolved = fixtures.fileIssue(s.customerA(), s.a().code()).key();
        String closed = fixtures.fileIssue(s.customerA(), s.a().code()).key();
        long ofResolved = taskFor(s, resolved, s.agent(), null);
        long deleted = taskFor(s, resolved, s.agent(), null);
        long openOfClosed = taskFor(s, closed, s.agent(), null);
        long doneOfClosed = taskFor(s, closed, s.agent(), null);
        long doneOfResolved = taskFor(s, resolved, s.agent(), null);
        TaskSteps.update(s.admin(), closed, doneOfClosed, body("status", "DONE"));
        TaskSteps.update(s.admin(), resolved, doneOfResolved, body("status", "DONE"));
        assertThat(open(s.agent())).as("OPEN tasks before").containsExactly(ofResolved, deleted, openOfClosed);
        assertThat(done(s.agent())).as("DONE tasks before").containsExactlyInAnyOrder(doneOfClosed, doneOfResolved);

        TaskSteps.delete(s.admin(), resolved, deleted);
        flow.moveTo(s.admin(), resolved, "RESOLVED");
        flow.changeStatus(s.admin(), closed, "CLOSED");

        assertThat(open(s.agent())).as("OPEN tasks after").containsExactly(ofResolved);
        assertThat(done(s.agent())).as("DONE tasks after").containsExactly(doneOfResolved);
    }

    @Test
    @DisplayName("S6B-AC-62 an agent taken off company A (without clearing) no longer has A's task in my tasks, "
            + "though still its assignee; assigned again, they have it back; the task of B stays throughout")
    void taskOfACompanyTheAgentLeftIsLeftOut() {
        Scene s = scene();
        String ofA = fixtures.fileIssue(s.customerA(), s.a().code()).key();
        String ofB = fixtures.fileIssue(s.customerB(), s.b().code()).key();
        long inA = taskFor(s, ofA, s.agent(), today().plusDays(1));
        long inB = taskFor(s, ofB, s.agent(), null);
        long doneInA = taskFor(s, ofA, s.agent(), null);
        TaskSteps.update(s.admin(), ofA, doneInA, body("status", "DONE"));
        assertThat(open(s.agent())).as("OPEN tasks while assigned to A").containsExactly(inA, inB);
        assertThat(done(s.agent())).as("DONE tasks while assigned to A").containsExactly(doneInA);

        flow.unassignAgent(s.admin(), s.a().code(), s.agent().accountId());

        assertThat(TaskSteps.personId(TaskSteps.task(s.admin(), ofA, inA), "assignee"))
                .as("assignee of A's task after the agent was taken off A").isEqualTo(s.agent().accountId());
        ApiResponse page = TaskSteps.myTasks(s.agent(), "size", 100);
        assertThat(ids(new ArrayList<>(page.json().path("items").values()))).as("OPEN tasks off A")
                .containsExactly(inB);
        assertThat(page.jsonLong("totalElements")).as("totalElements off A").isEqualTo(1);
        assertThat(done(s.agent())).as("DONE tasks off A").isEmpty();
        assertThat(page.text()).as("my tasks off A").doesNotContain(ofA).doesNotContain(s.a().code());

        fixtures.assignAgent(s.admin(), s.a().code(), s.agent().accountId());

        assertThat(open(s.agent())).as("OPEN tasks assigned to A again").containsExactly(inA, inB);
        assertThat(done(s.agent())).as("DONE tasks assigned to A again").containsExactly(doneInA);
    }

    // S6B-AC-63

    @Test
    @DisplayName("S6B-AC-63 the task edit with status DONE, as \"done\" in my tasks sends it, moves the task "
            + "from the OPEN tasks to the DONE ones with its done time")
    void doneFromMyTasks() {
        Scene s = scene();
        String key = fixtures.fileIssue(s.customerA(), s.a().code()).key();
        long task = taskFor(s, key, s.agent(), null);
        long other = taskFor(s, key, s.agent(), null);
        flow.tick();

        JsonNode edited = TaskSteps.update(s.agent(), key, task, body("status", "DONE"));

        assertThat(text(edited, "status")).as("status of the answer").isEqualTo("DONE");
        assertThat(open(s.agent())).as("OPEN tasks").containsExactly(other);
        List<JsonNode> doneItems = all(s.agent(), "DONE", 100);
        assertThat(ids(doneItems)).as("DONE tasks").containsExactly(task);
        assertThat(IssueFlow.time(doneItems.getFirst(), "doneAt")).as("doneAt").isEqualTo(clock.instant());
        assertThat(text(doneItems.getFirst(), "status")).as("status in my tasks").isEqualTo("DONE");
    }
}
