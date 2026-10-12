package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.json;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.issue.IssueFlow.suspendRequest;
import static io.popdesk.issue.IssueFlow.unassignAgentRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit entries of taking an agent off a company and of suspending an account when the admin
 * also clears the person's open issues ({@code clearAssignments=true}): the changed values hold the
 * number of cleared issues as {@code clearedIssues}. Without clearing, the entry is what it was
 * before slice 3.
 */
class ClearAssignmentsAuditTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** {@code n} open issues of the company with the agent as their assignee. */
    private void assignOpenIssues(Session admin, Company company, Session customer, Session agent, int n) {
        for (int i = 0; i < n; i++) {
            Issue issue = fixtures.fileIssue(customer, company.code());
            flow.assign(admin, issue.key(), agent.accountId());
        }
    }

    @Test
    @DisplayName("S3-AC-72 the entry of taking an agent off with cleared issues holds clearedIssues as a number next to what it held; without clearing it has no such key; the chain is valid")
    void unassignedAgentEntry() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        assignOpenIssues(admin, company, customer, agent, 2);

        unassignAgentRequest(admin, company.code(), agent.accountId()).expect(200);
        JsonNode plain = onlyEntry(admin, "targetAccountId", agent.accountId(), "action", "AGENT_UNASSIGNED");
        fixtures.assignAgent(admin, company.code(), agent.accountId());
        flow.tick();
        unassignAgentRequest(admin, company.code(), agent.accountId()).query("clearAssignments", true).expect(200);

        List<JsonNode> both = entries(admin, "targetAccountId", agent.accountId(), "action", "AGENT_UNASSIGNED");
        assertThat(both).as("entries of taking the agent off twice").hasSize(2);
        JsonNode cleared = both.getFirst();
        assertThat(plain.path("changes").has("clearedIssues")).as("clearedIssues in the entry without clearing: %s",
                plain).isFalse();
        assertThat(cleared.path("changes").path("clearedIssues").isIntegralNumber())
                .as("clearedIssues is a number in %s", cleared).isTrue();
        assertThat(cleared.path("changes").path("clearedIssues").asLong(-1)).as("clearedIssues").isEqualTo(2);
        ObjectNode rest = ((ObjectNode) cleared.path("changes")).deepCopy();
        rest.remove("clearedIssues");
        assertThat(canonical(rest)).as("the other changed values, like without clearing")
                .isEqualTo(canonical(plain.path("changes")));
        AuditChainMath.assertValid(admin.client());
    }

    @Test
    @DisplayName("S3-AC-72 the entry of a suspension with cleared issues holds the status change and clearedIssues as a number, 0 when clearing was asked for and there was no issue; without clearing, only the status change; the chain is valid")
    void suspendedAccountEntry() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session clearedAgent = fixtures.agent(admin, company.code());
        Session keptAgent = fixtures.agent(admin, company.code());
        Session idleAgent = fixtures.agent(admin, company.code());
        assignOpenIssues(admin, company, customer, clearedAgent, 3);
        assignOpenIssues(admin, company, customer, keptAgent, 1);
        Map<String, Object> statusChange = Map.of("from", "ACTIVE", "to", "SUSPENDED");

        suspendRequest(admin, clearedAgent.accountId()).query("clearAssignments", true).expect(200);
        suspendRequest(admin, keptAgent.accountId()).expect(200);
        suspendRequest(admin, idleAgent.accountId()).query("clearAssignments", true).expect(200);

        JsonNode cleared = onlyEntry(admin, "targetAccountId", clearedAgent.accountId(), "action",
                "ACCOUNT_SUSPENDED");
        JsonNode kept = onlyEntry(admin, "targetAccountId", keptAgent.accountId(), "action", "ACCOUNT_SUSPENDED");
        JsonNode idle = onlyEntry(admin, "targetAccountId", idleAgent.accountId(), "action", "ACCOUNT_SUSPENDED");
        assertThat(cleared.path("changes").path("clearedIssues").isIntegralNumber())
                .as("clearedIssues is a number in %s", cleared).isTrue();
        assertThat(canonical(cleared.path("changes"))).as("changes of the suspension with clearing")
                .isEqualTo(canonical(json(Map.of("status", statusChange, "clearedIssues", 3))));
        assertThat(canonical(kept.path("changes"))).as("changes of the suspension without clearing")
                .isEqualTo(canonical(json(Map.of("status", statusChange))));
        assertThat(idle.path("changes").path("clearedIssues").isIntegralNumber())
                .as("clearedIssues is a number in %s", idle).isTrue();
        assertThat(canonical(idle.path("changes"))).as("changes of the suspension that cleared no issue")
                .isEqualTo(canonical(json(Map.of("status", statusChange, "clearedIssues", 0))));
        AuditChainMath.assertValid(admin.client());
    }
}
