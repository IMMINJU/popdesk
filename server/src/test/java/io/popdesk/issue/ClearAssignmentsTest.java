package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Open issues a person is the assignee of (ACCT-7): the two counts an admin reads before taking an
 * agent off a company or suspending an account, and {@code clearAssignments=true} on those two
 * requests, which clears the assignee of the issues the count told of.
 */
class ClearAssignmentsTest extends BareAppTest {

    /**
     * Companies A and B, a customer of each, and one person who is the assignee of two open issues
     * and a closed one in A, and of one open issue in B.
     */
    private record Scene(Session admin, Company a, Company b, Session assignee, Issue openA1, Issue openA2,
            Issue closedA, Issue openB) {

        List<Issue> issues() {
            return List.of(openA1, openA2, closedA, openB);
        }
    }

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The scene with an agent assigned to both companies as the assignee. */
    private Scene sceneOfAnAgent() {
        Session admin = flow.admin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        return scene(admin, a, b, fixtures.agent(admin, a.code(), b.code()));
    }

    private Scene scene(Session admin, Company a, Company b, Session assignee) {
        Session customerA = fixtures.customer(admin, a.code());
        Session customerB = fixtures.customer(admin, b.code());
        Issue openA1 = fixtures.fileIssue(customerA, a.code());
        Issue openA2 = fixtures.fileIssue(customerA, a.code());
        Issue closedA = fixtures.fileIssue(customerA, a.code());
        Issue openB = fixtures.fileIssue(customerB, b.code());
        for (Issue issue : List.of(openA1, openA2, closedA, openB)) {
            flow.assign(admin, issue.key(), assignee.accountId());
        }
        flow.moveTo(admin, openA2.key(), "RESOLVED");
        flow.changeStatus(admin, closedA.key(), "CLOSED");
        return new Scene(admin, a, b, assignee, openA1, openA2, closedA, openB);
    }

    private static ApiRequest companyCount(Session by, String code, long accountId) {
        return by.client().get("/api/console/companies/" + code + "/agents/" + accountId + "/open-assignments");
    }

    private static ApiRequest accountCount(Session by, long accountId) {
        return by.client().get("/api/console/accounts/" + accountId + "/open-assignments");
    }

    private static ApiRequest unassignAgent(Session admin, String code, long accountId) {
        return admin.client().delete("/api/console/companies/" + code + "/agents/" + accountId);
    }

    private static ApiRequest suspend(Session admin, long accountId) {
        return admin.client().post("/api/console/accounts/" + accountId + "/suspend");
    }

    /** The account ID of the assignee as an admin reads the issue; 0 when there is none. */
    private long assigneeOf(Session admin, Issue issue) {
        JsonNode assignee = flow.detail(admin, issue.key()).path("assignee");
        return assignee.isNull() ? 0 : assignee.path("accountId").asLong(-1);
    }

    private List<Long> assignees(Scene s) {
        return s.issues().stream().map(issue -> assigneeOf(s.admin(), issue)).toList();
    }

    // S3-AC-68

    @Test
    @DisplayName("S3-AC-68 the count for a company is the open issues the agent is the assignee of there; the count for the account, in every company; closed issues are left out")
    void countsOpenAssignments() {
        Scene s = sceneOfAnAgent();
        long id = s.assignee().accountId();

        assertThat(companyCount(s.admin(), s.a().code(), id).expect(200).jsonLong("issues"))
                .as("open issues of the agent in A").isEqualTo(2);
        assertThat(companyCount(s.admin(), s.b().code(), id).expect(200).jsonLong("issues"))
                .as("open issues of the agent in B").isEqualTo(1);
        assertThat(accountCount(s.admin(), id).expect(200).jsonLong("issues"))
                .as("open issues of the agent's account").isEqualTo(3);
    }

    @Test
    @DisplayName("S3-AC-68 the count for the account of an admin who is an assignee is their open issues in every company")
    void countsOpenAssignmentsOfAnAdmin() {
        Session first = flow.admin();
        Session admin = fixtures.admin(first);
        Scene s = scene(first, fixtures.createCompany(first), fixtures.createCompany(first), admin);

        assertThat(accountCount(s.admin(), admin.accountId()).expect(200).jsonLong("issues"))
                .as("open issues of the admin's account").isEqualTo(3);
    }

    @Test
    @DisplayName("S3-AC-68 an agent asking for either count gets 403")
    void agentsCannotCount() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());

        ApiResponse ofCompany = companyCount(agent, company.code(), agent.accountId()).send();
        ApiResponse ofAccount = accountCount(agent, agent.accountId()).send();

        for (ApiResponse answer : List.of(ofCompany, ofAccount)) {
            assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(403);
            assertThat(answer.errorCode()).isEqualTo("FORBIDDEN");
        }
    }

    // S3-AC-69, S3-AC-70

    @Test
    @DisplayName("S3-AC-69 taking an agent off company A with clearAssignments=true clears the two open issues of A, answers 2, and leaves the closed issue and the issue of B")
    void unassigningClearsTheOpenIssuesOfThatCompany() {
        Scene s = sceneOfAnAgent();
        long id = s.assignee().accountId();

        ApiResponse answer = unassignAgent(s.admin(), s.a().code(), id).query("clearAssignments", true).expect(200);

        assertThat(answer.jsonLong("issues")).as("issues in the response").isEqualTo(2);
        assertThat(assignees(s)).as("assignees of open A, open A, closed A, open B").containsExactly(0L, 0L, id, id);
        assertThat(companyCount(s.admin(), s.b().code(), id).expect(200).jsonLong("issues"))
                .as("open issues of the agent in B afterwards").isEqualTo(1);
    }

    @Test
    @DisplayName("S3-AC-69 suspending an account with clearAssignments=true clears its open issues in every company, answers their number, and leaves the closed issue")
    void suspendingClearsEveryOpenIssue() {
        Scene s = sceneOfAnAgent();
        long id = s.assignee().accountId();

        ApiResponse answer = suspend(s.admin(), id).query("clearAssignments", true).expect(200);

        assertThat(text(answer.json(), "status")).isEqualTo("SUSPENDED");
        assertThat(answer.json().path("cleared").path("issues").asLong(-1)).as("cleared.issues in %s",
                answer.snippet()).isEqualTo(3);
        assertThat(assignees(s)).as("assignees of open A, open A, closed A, open B").containsExactly(0L, 0L, id, 0L);
    }

    @ParameterizedTest(name = "clearAssignments {0}")
    @ValueSource(strings = {"(left out)", "false"})
    @DisplayName("S3-AC-69 without clearAssignments, or with false, taking an agent off and suspending leave every assignee as it is")
    void withoutClearingAssigneesStay(String value) {
        Scene s = sceneOfAnAgent();
        long id = s.assignee().accountId();
        ApiRequest unassign = unassignAgent(s.admin(), s.a().code(), id);
        ApiRequest suspension = suspend(s.admin(), id);
        if (!value.equals("(left out)")) {
            unassign.query("clearAssignments", value);
            suspension.query("clearAssignments", value);
        }

        ApiResponse unassigned = unassign.expect(200);

        assertThat(unassigned.body()).as("body of taking the agent off").isEmpty();
        assertThat(assignees(s)).as("assignees after taking the agent off").containsExactly(id, id, id, id);

        ApiResponse suspended = suspension.expect(200);

        assertThat(suspended.json().path("cleared").path("issues").asLong(-1)).as("cleared.issues in %s",
                suspended.snippet()).isZero();
        assertThat(assignees(s)).as("assignees after the suspension").containsExactly(id, id, id, id);
        // The two requests did what they do today; the count still tells of the issues left behind.
        assertThat(accountCount(s.admin(), id).expect(200).jsonLong("issues")).as("open issues of the account")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("S3-AC-70 each cleared issue gets one assignee entry in its history by the admin and a version raised by 1; the closed issue gets neither")
    void clearedIssuesGetAHistoryEntryByTheAdmin() {
        Session first = flow.admin();
        Session admin = fixtures.admin(first);
        Company a = fixtures.createCompany(admin);
        Scene s = scene(admin, a, fixtures.createCompany(admin), fixtures.agent(admin, a.code()));
        long id = s.assignee().accountId();
        List<Long> versions = s.issues().stream().map(issue -> flow.version(admin, issue.key())).toList();
        List<Integer> entries = s.issues().stream().map(issue -> flow.history(admin, issue.key()).size()).toList();
        flow.tick();

        suspend(admin, id).query("clearAssignments", true).expect(200);

        for (int i = 0; i < s.issues().size(); i++) {
            Issue issue = s.issues().get(i);
            boolean closed = issue == s.closedA();
            List<JsonNode> history = flow.history(admin, issue.key());
            assertThat(flow.version(admin, issue.key())).as("version of %s", issue.key())
                    .isEqualTo(versions.get(i) + (closed ? 0 : 1));
            assertThat(history).as("history of %s: %s", issue.key(), history)
                    .hasSize(entries.get(i) + (closed ? 0 : 1));
            if (!closed) {
                JsonNode last = history.getLast();
                assertThat(text(last, "kind") + " " + text(last, "field")).isEqualTo("CHANGED ASSIGNEE");
                assertThat(last.path("oldPerson").path("accountId").asLong(-1)).as("oldPerson of %s", last)
                        .isEqualTo(id);
                assertThat(last.path("newPerson").isNull()).as("newPerson is null in %s", last).isTrue();
                assertThat(last.path("actor").path("accountId").asLong(-1)).as("actor of %s", last)
                        .isEqualTo(admin.accountId());
                assertThat(IssueFlow.time(last, "createdAt")).as("createdAt of %s", last).isEqualTo(clock.instant());
            }
        }
    }

    // S3-AC-71

    @Test
    @DisplayName("S3-AC-71 an admin suspending their own account with clearAssignments=true gets 400 OWN_ACCOUNT, and the issues they are the assignee of stay theirs")
    void refusedSuspensionClearsNothing() {
        Session first = flow.admin();
        Session admin = fixtures.admin(first);
        Scene s = scene(first, fixtures.createCompany(first), fixtures.createCompany(first), admin);
        long id = admin.accountId();
        List<Long> versions = s.issues().stream().map(issue -> flow.version(first, issue.key())).toList();

        ApiResponse refused = suspend(admin, id).query("clearAssignments", true).send();

        assertThat(refused.status()).as(refused.request() + " -> " + refused.snippet()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo("OWN_ACCOUNT");
        assertThat(assignees(s)).as("assignees after the refused suspension").containsExactly(id, id, id, id);
        assertThat(s.issues().stream().map(issue -> flow.version(first, issue.key())).toList())
                .as("versions after the refused suspension").isEqualTo(versions);
        assertThat(accountCount(first, id).expect(200).jsonLong("issues")).as("open issues of the admin's account")
                .isEqualTo(3);
    }
}
