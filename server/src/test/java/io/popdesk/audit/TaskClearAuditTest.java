package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.json;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.issue.IssueFlow.suspendRequest;
import static io.popdesk.issue.IssueFlow.unassignAgentRequest;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.title;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.task.TaskSteps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit entries of taking an agent off a company and of suspending an account with
 * {@code clearAssignments=true} when tasks were cleared (slice 6b, S6B-AC-76): the changed values hold the
 * number of cleared tasks as {@code clearedTasks} next to {@code clearedIssues}, only when there was a task to
 * clear. What the entry holds without tasks is {@link ClearAssignmentsAuditTest}.
 */
class TaskClearAuditTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** {@code n} OPEN tasks of new open issues of the company with the agent as their assignee; no issue is theirs. */
    private void giveTasks(Session admin, Company company, Session customer, Session agent, int n) {
        String key = fixtures.fileIssue(customer, company.code()).key();
        for (int i = 0; i < n; i++) {
            TaskSteps.create(admin, key, body("title", title("Audit"), "assigneeId", agent.accountId()));
        }
    }

    @Test
    @DisplayName("S6B-AC-76 the entry of taking an agent off with clearing holds clearedTasks as a number when "
            + "tasks were cleared, also with no issue cleared, and is otherwise the entry of clearing without "
            + "tasks, which has no clearedTasks; the chain is valid")
    void unassignedAgentEntry() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());

        unassignAgentRequest(admin, company.code(), agent.accountId()).query("clearAssignments", true).expect(200);
        JsonNode withoutTasks = onlyEntry(admin, "targetAccountId", agent.accountId(), "action", "AGENT_UNASSIGNED");
        fixtures.assignAgent(admin, company.code(), agent.accountId());
        giveTasks(admin, company, customer, agent, 2);
        flow.tick();
        unassignAgentRequest(admin, company.code(), agent.accountId()).query("clearAssignments", true).expect(200);

        List<JsonNode> both = entries(admin, "targetAccountId", agent.accountId(), "action", "AGENT_UNASSIGNED");
        assertThat(both).as("entries of taking the agent off twice").hasSize(2);
        JsonNode withTasks = both.getFirst();
        assertThat(withoutTasks.path("changes").has("clearedTasks"))
                .as("clearedTasks in the entry that cleared no task: %s", withoutTasks).isFalse();
        assertThat(withTasks.path("changes").path("clearedTasks").isIntegralNumber())
                .as("clearedTasks is a number in %s", withTasks).isTrue();
        assertThat(withTasks.path("changes").path("clearedTasks").asLong(-1)).as("clearedTasks").isEqualTo(2);
        ObjectNode rest = ((ObjectNode) withTasks.path("changes")).deepCopy();
        rest.remove("clearedTasks");
        assertThat(canonical(rest)).as("the other changed values, like clearing without tasks")
                .isEqualTo(canonical(withoutTasks.path("changes")));
        AuditChainMath.assertValid(admin.client());
    }

    @Test
    @DisplayName("S6B-AC-76 the entry of a suspension that cleared tasks and no issue is the status change, "
            + "clearedIssues 0, and clearedTasks; one that cleared an issue and no task has no clearedTasks; the "
            + "chain is valid")
    void suspendedAccountEntry() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session tasksOnly = fixtures.agent(admin, company.code());
        Session issueOnly = fixtures.agent(admin, company.code());
        giveTasks(admin, company, customer, tasksOnly, 3);
        flow.assign(admin, fixtures.fileIssue(customer, company.code()).key(), issueOnly.accountId());
        Map<String, Object> statusChange = Map.of("from", "ACTIVE", "to", "SUSPENDED");

        suspendRequest(admin, tasksOnly.accountId()).query("clearAssignments", true).expect(200);
        suspendRequest(admin, issueOnly.accountId()).query("clearAssignments", true).expect(200);

        JsonNode ofTasks = onlyEntry(admin, "targetAccountId", tasksOnly.accountId(), "action", "ACCOUNT_SUSPENDED");
        JsonNode ofIssue = onlyEntry(admin, "targetAccountId", issueOnly.accountId(), "action", "ACCOUNT_SUSPENDED");
        assertThat(ofTasks.path("changes").path("clearedTasks").isIntegralNumber())
                .as("clearedTasks is a number in %s", ofTasks).isTrue();
        assertThat(canonical(ofTasks.path("changes"))).as("changes of the suspension that cleared three tasks")
                .isEqualTo(canonical(json(Map.of("status", statusChange, "clearedIssues", 0, "clearedTasks", 3))));
        assertThat(canonical(ofIssue.path("changes"))).as("changes of the suspension that cleared one issue")
                .isEqualTo(canonical(json(Map.of("status", statusChange, "clearedIssues", 1))));
        AuditChainMath.assertValid(admin.client());
    }
}
