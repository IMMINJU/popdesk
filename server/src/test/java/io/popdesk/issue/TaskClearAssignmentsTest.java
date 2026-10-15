package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.suspendRequest;
import static io.popdesk.issue.IssueFlow.unassignAgentRequest;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.title;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.task.TaskSteps;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The tasks of ACCT-7 (slice 6b, S6B-AC-75, 76): the open tasks a person is the assignee of, counted next to
 * the open issues before an admin takes an agent off a company or suspends an account, and cleared with
 * {@code clearAssignments=true}. The issue part is {@link ClearAssignmentsTest}; the audit entry is
 * {@code audit.TaskClearAuditTest}.
 */
class TaskClearAssignmentsTest extends BareAppTest {

    /**
     * Companies A and B and an agent assigned to both who is the assignee of no issue but of these tasks: two
     * OPEN tasks on two issues of A that are not closed (one RESOLVED), a DONE and a deleted task of A, an OPEN
     * task of a CLOSED issue of A, and an OPEN task of B. Another agent of A has an OPEN task of A.
     */
    private record Scene(Session admin, Company a, Company b, Session agent, Session other, String issueA1,
            String issueA2, String closedA, String issueB, long open1, long open2, long done, long deleted,
            long ofClosed, long ofB, long othersTask) {

        /** Every task of the scene with the key of its issue, deleted one left out. */
        List<Object[]> tasks() {
            return List.of(new Object[] {issueA1, open1}, new Object[] {issueA2, open2},
                    new Object[] {issueA1, done}, new Object[] {closedA, ofClosed}, new Object[] {issueB, ofB},
                    new Object[] {issueA1, othersTask});
        }
    }

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private Scene scene() {
        Session admin = fixtures.admin(flow.admin());
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session customerA = fixtures.customer(admin, a.code());
        Session customerB = fixtures.customer(admin, b.code());
        Session agent = fixtures.agent(admin, a.code(), b.code());
        Session other = fixtures.agent(admin, a.code());
        String issueA1 = fixtures.fileIssue(customerA, a.code()).key();
        String issueA2 = fixtures.fileIssue(customerA, a.code()).key();
        String closedA = fixtures.fileIssue(customerA, a.code()).key();
        String issueB = fixtures.fileIssue(customerB, b.code()).key();
        long open1 = taskFor(admin, issueA1, agent);
        long open2 = taskFor(admin, issueA2, agent);
        long done = taskFor(admin, issueA1, agent);
        long deleted = taskFor(admin, issueA1, agent);
        long ofClosed = taskFor(admin, closedA, agent);
        long ofB = taskFor(admin, issueB, agent);
        long othersTask = taskFor(admin, issueA1, other);
        TaskSteps.update(admin, issueA1, done, body("status", "DONE"));
        TaskSteps.delete(admin, issueA1, deleted);
        flow.moveTo(admin, issueA2, "RESOLVED");
        flow.changeStatus(admin, closedA, "CLOSED");
        return new Scene(admin, a, b, agent, other, issueA1, issueA2, closedA, issueB, open1, open2, done, deleted,
                ofClosed, ofB, othersTask);
    }

    private static long taskFor(Session admin, String key, Session assignee) {
        return id(TaskSteps.create(admin, key, body("title", title("Held"), "assigneeId", assignee.accountId())));
    }

    private static ApiRequest companyCount(Session by, String code, long accountId) {
        return by.client().get("/api/console/companies/" + code + "/agents/" + accountId + "/open-assignments");
    }

    private static ApiRequest accountCount(Session by, long accountId) {
        return by.client().get("/api/console/accounts/" + accountId + "/open-assignments");
    }

    /** The account ID of each task's assignee in the order of {@link Scene#tasks()}; 0 for none. */
    private static List<Long> assignees(Scene s) {
        List<Long> out = new ArrayList<>();
        for (Object[] t : s.tasks()) {
            Long assignee = TaskSteps.personId(TaskSteps.task(s.admin(), (String) t[0], (Long) t[1]), "assignee");
            out.add(assignee == null ? 0 : assignee);
        }
        return out;
    }

    // S6B-AC-75

    @Test
    @DisplayName("S6B-AC-75 the counts give the OPEN tasks the person is the assignee of on issues that are not "
            + "CLOSED next to the issues: for a company only that company's, for the account every company's; DONE, "
            + "deleted, and others' tasks and the task of a CLOSED issue are not counted")
    void countsOpenTasks() {
        Scene s = scene();
        long id = s.agent().accountId();

        JsonNode ofA = companyCount(s.admin(), s.a().code(), id).expect(200).json();
        JsonNode ofB = companyCount(s.admin(), s.b().code(), id).expect(200).json();
        JsonNode ofAccount = accountCount(s.admin(), id).expect(200).json();

        assertThat(ofA.path("tasks").asLong(-1)).as("open tasks of the agent in A: %s", ofA).isEqualTo(2);
        assertThat(ofB.path("tasks").asLong(-1)).as("open tasks of the agent in B: %s", ofB).isEqualTo(1);
        assertThat(ofAccount.path("tasks").asLong(-1)).as("open tasks of the agent's account: %s", ofAccount)
                .isEqualTo(3);
        for (JsonNode count : List.of(ofA, ofB, ofAccount)) {
            assertThat(count.path("issues").asLong(-1)).as("open issues of the agent: %s", count).isZero();
        }
        assertThat(accountCount(s.admin(), s.other().accountId()).expect(200).jsonLong("tasks"))
                .as("open tasks of the other agent").isEqualTo(1);
    }

    // S6B-AC-76

    @Test
    @DisplayName("S6B-AC-76 taking the agent off A with clearAssignments=true clears the assignee of the two OPEN "
            + "tasks of A's issues that are not closed, each with an ASSIGNEE change by the admin; the DONE task, "
            + "the task of the CLOSED issue, B's task, and the other agent's stay; no inbox item is added")
    void unassigningClearsTheOpenTasksOfThatCompany() {
        Scene s = scene();
        long id = s.agent().accountId();
        List<JsonNode> agentItems = TalkFlow.inbox(s.agent());
        List<JsonNode> otherItems = TalkFlow.inbox(s.other());
        assertThat(agentItems).as("the agent's items before").isNotEmpty();
        flow.tick();

        unassignAgentRequest(s.admin(), s.a().code(), id).query("clearAssignments", true).expect(200);

        assertThat(assignees(s)).as("assignees of open A1, open A2, done A1, of closed A, of B, other's")
                .containsExactly(0L, 0L, id, id, id, s.other().accountId());
        for (Object[] t : List.of(new Object[] {s.issueA1(), s.open1()}, new Object[] {s.issueA2(), s.open2()})) {
            assertClearedBy(s, (String) t[0], (Long) t[1], id);
        }
        assertThat(TaskSteps.changes(TaskSteps.task(s.admin(), s.issueB(), s.ofB()))).as("changes of B's task")
                .noneMatch(c -> clears(c, id));
        // The inbox shows only items of issues the agent can see now (S4-AC-42), so the items of A's issues
        // leave the list; no item is added.
        assertThat(TalkFlow.ids(TalkFlow.inbox(s.agent()))).as("the agent's items after")
                .isSubsetOf(TalkFlow.ids(agentItems));
        assertThat(TalkFlow.ids(TalkFlow.inbox(s.other()))).as("the other agent's items after")
                .isEqualTo(TalkFlow.ids(otherItems));
        assertThat(accountCount(s.admin(), id).expect(200).jsonLong("tasks")).as("open tasks of the account after")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("S6B-AC-76 suspending the agent with clearAssignments=true clears the OPEN tasks of every "
            + "company's issues that are not closed; the DONE task and the task of the CLOSED issue stay")
    void suspendingClearsEveryOpenTask() {
        Scene s = scene();
        long id = s.agent().accountId();
        flow.tick();

        suspendRequest(s.admin(), id).query("clearAssignments", true).expect(200);

        assertThat(assignees(s)).as("assignees of open A1, open A2, done A1, of closed A, of B, other's")
                .containsExactly(0L, 0L, id, id, 0L, s.other().accountId());
        assertClearedBy(s, s.issueB(), s.ofB(), id);
    }

    @Test
    @DisplayName("S6B-AC-76 without clearAssignments, taking the agent off and suspending leave every task "
            + "assignee as it is")
    void withoutClearingTasksStay() {
        Scene s = scene();
        long id = s.agent().accountId();

        unassignAgentRequest(s.admin(), s.a().code(), id).expect(200);
        suspendRequest(s.admin(), id).expect(200);

        assertThat(assignees(s)).as("assignees of open A1, open A2, done A1, of closed A, of B, other's")
                .containsExactly(id, id, id, id, id, s.other().accountId());
        for (Object[] t : s.tasks()) {
            assertThat(TaskSteps.changes(TaskSteps.task(s.admin(), (String) t[0], (Long) t[1])))
                    .as("changes of task %s", t[1]).noneMatch(c -> clears(c, id));
        }
    }

    /** Whether the entry is a change of ASSIGNEE from this account to nobody. */
    private static boolean clears(JsonNode change, long accountId) {
        return "ASSIGNEE".equals(TaskSteps.text(change, "field")) && change.path("newPerson").isNull()
                && change.path("oldPerson").path("accountId").asLong(-1) == accountId;
    }

    /**
     * The task's only change is the one of ASSIGNEE from the agent to nobody, by the admin, at the clock's time,
     * and it is the last entry. Making the task with its assignee left no entry (S6B-AC-30).
     */
    private void assertClearedBy(Scene s, String key, long task, long agentId) {
        JsonNode t = TaskSteps.task(s.admin(), key, task);
        List<JsonNode> changes = TaskSteps.changes(t);
        assertThat(changes).as("changes of task %s", task).hasSize(1);
        JsonNode change = changes.getFirst();
        assertThat(clears(change, agentId)).as("%s is the change of ASSIGNEE from the agent to nobody", change)
                .isTrue();
        assertThat(TaskSteps.personId(change, "author")).as("author of %s", change).isEqualTo(s.admin().accountId());
        assertThat(IssueFlow.time(change, "createdAt")).as("createdAt of %s", change).isEqualTo(clock.instant());
        assertThat(TaskSteps.id(TaskSteps.notes(t).getLast())).as("the last entry of task %s", task)
                .isEqualTo(TaskSteps.id(change));
    }
}
