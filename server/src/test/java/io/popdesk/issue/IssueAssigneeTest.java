package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The assignee of an issue: setting and clearing it, who can be one, the candidates of a company,
 * and the mark for an assignee who can no longer see the issue.
 */
class IssueAssigneeTest extends BareAppTest {

    /** An account ID no account has. */
    private static final long NO_ACCOUNT = 4_000_000_000_000L;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static ApiResponse setAssignee(Session staff, String key, long accountId) {
        return staff.client().put("/api/console/issues/" + key + "/assignee").json(Map.of("accountId", accountId))
                .send();
    }

    private static ApiResponse candidates(Session staff, String code) {
        return staff.client().get("/api/console/companies/" + code + "/assignees").send();
    }

    private static long assigneeId(JsonNode issue) {
        return issue.path("assignee").path("accountId").asLong(-1);
    }

    private static long version(JsonNode issue) {
        return issue.path("version").asLong(-1);
    }

    /** The issue as the console list of its company shows it to {@code staff}. */
    private static JsonNode listed(Session staff, String code, String key) {
        return items(IssueFlow.list(staff, "companyCode", code)).stream()
                .filter(n -> key.equals(text(n, "key"))).findFirst()
                .orElseThrow(() -> new AssertionError("Issue " + key + " is not in the console list of " + code));
    }

    private static void assertNoAssignee(String what, JsonNode issue) {
        assertThat(issue.has("assignee")).as("%s has the field assignee: %s", what, issue).isTrue();
        assertThat(issue.path("assignee").isNull()).as("assignee is null in %s", what).isTrue();
        assertThat(issue.path("assigneeUnavailable").isBoolean()).as("%s has the flag assigneeUnavailable: %s", what,
                issue).isTrue();
        assertThat(issue.path("assigneeUnavailable").booleanValue()).as("assigneeUnavailable in %s", what).isFalse();
    }

    // S3-AC-24

    @Test
    @DisplayName("S3-AC-24 an agent assigns themself, an admin an agent, an agent an admin: each raises the version by 1, and the same person again changes nothing")
    void staffSetAndChangeTheAssignee() {
        World w = flow.world();
        Session otherAgent = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        long start = flow.version(w.agent(), issue.key());

        JsonNode byAgent = flow.assign(w.agent(), issue.key(), w.agent().accountId());

        assertThat(assigneeId(byAgent)).as("assignee after the agent took the issue").isEqualTo(w.agent().accountId());
        assertThat(text(byAgent.path("assignee"), "name")).isEqualTo(w.agent().name());
        assertThat(text(byAgent.path("assignee"), "role")).isEqualTo("AGENT");
        assertThat(version(byAgent)).as("version after the first assignee").isEqualTo(start + 1);
        assertThat(assigneeId(listed(w.agent(), w.code(), issue.key()))).as("assignee in the list")
                .isEqualTo(w.agent().accountId());

        JsonNode byAdmin = flow.assign(w.admin(), issue.key(), otherAgent.accountId());

        assertThat(assigneeId(byAdmin)).as("assignee the admin set").isEqualTo(otherAgent.accountId());
        assertThat(version(byAdmin)).as("version after the second assignee").isEqualTo(start + 2);

        JsonNode toAdmin = flow.assign(w.agent(), issue.key(), w.admin().accountId());

        assertThat(assigneeId(toAdmin)).as("an admin as assignee").isEqualTo(w.admin().accountId());
        assertThat(text(toAdmin.path("assignee"), "role")).isEqualTo("ADMIN");
        assertThat(version(toAdmin)).as("version after the third assignee").isEqualTo(start + 3);
        assertThat(assigneeId(listed(w.admin(), w.code(), issue.key()))).as("assignee in the list")
                .isEqualTo(w.admin().accountId());
        flow.tick();

        JsonNode again = flow.assign(w.agent(), issue.key(), w.admin().accountId());

        assertThat(assigneeId(again)).isEqualTo(w.admin().accountId());
        assertThat(version(again)).as("version after the same assignee again").isEqualTo(start + 3);
        assertThat(text(again, "updatedAt")).as("updatedAt after the same assignee again")
                .isEqualTo(text(toAdmin, "updatedAt"));
    }

    // S3-AC-25

    @Test
    @DisplayName("S3-AC-25 a customer, an invited agent, a suspended agent, an agent of no company, and an ID of nobody get the same 400 with accountId UNKNOWN_VALUE, and the assignee stays")
    void accountsThatCannotBeAssignedGetTheSame400() {
        World w = flow.world();
        Invitation invited = fixtures.invite(w.admin(), Role.AGENT);
        fixtures.assignAgent(w.admin(), w.code(), invited.accountId());
        Session suspended = fixtures.agent(w.admin(), w.code());
        flow.suspend(w.admin(), suspended.accountId());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode before = flow.assign(w.admin(), issue.key(), w.agent().accountId());
        Map<String, Long> accounts = new LinkedHashMap<>();
        accounts.put("a customer of the company", w.reporter().accountId());
        accounts.put("an invited agent of the company", invited.accountId());
        accounts.put("a suspended agent of the company", suspended.accountId());
        accounts.put("an agent who is not assigned", w.unassignedAgent().accountId());
        accounts.put("an ID of nobody", NO_ACCOUNT + Unique.number());

        Map<String, ApiResponse> answers = new LinkedHashMap<>();
        accounts.forEach((who, id) -> answers.put(who, setAssignee(w.admin(), issue.key(), id)));

        ApiResponse first = answers.values().iterator().next();
        answers.forEach((who, answer) -> {
            assertThat(answer.status()).as("status for %s", who).isEqualTo(400);
            assertThat(answer.errorCode()).as("code for %s", who).isEqualTo("INVALID_INPUT");
            assertThat(fieldCode(answer, "accountId")).as("field accountId for %s", who).isEqualTo("UNKNOWN_VALUE");
            assertThat(answer.text()).as("body for %s, like for a customer", who).isEqualTo(first.text());
        });
        assertThat(flow.detail(w.admin(), issue.key())).as("the issue after the refused assignees").isEqualTo(before);
    }

    // S3-AC-26

    @Test
    @DisplayName("S3-AC-26 clearing the assignee leaves null and raises the version by 1; clearing an empty assignee is 200 and changes nothing")
    void clearingTheAssignee() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode assigned = flow.assign(w.agent(), issue.key(), w.agent().accountId());

        JsonNode cleared = flow.unassign(w.agent(), issue.key());

        assertNoAssignee("the response of clearing", cleared);
        assertThat(version(cleared)).as("version after clearing").isEqualTo(version(assigned) + 1);
        assertNoAssignee("the issue read again", flow.detail(w.admin(), issue.key()));

        JsonNode again = flow.unassign(w.admin(), issue.key());

        assertNoAssignee("the response of clearing again", again);
        assertThat(version(again)).as("version after clearing an empty assignee").isEqualTo(version(cleared));
    }

    // S3-AC-27

    @Test
    @DisplayName("S3-AC-27 the candidates of a company are the active admins and its assigned active agents, with accountId, name, and role")
    void candidatesAreActiveAdminsAndAssignedActiveAgents() {
        World w = flow.world();
        Session secondAdmin = fixtures.admin(w.admin());
        Session suspendedAdmin = fixtures.admin(w.admin());
        flow.suspend(w.admin(), suspendedAdmin.accountId());
        Invitation invitedAdmin = fixtures.invite(w.admin(), Role.ADMIN);
        Invitation invitedAgent = fixtures.invite(w.admin(), Role.AGENT);
        fixtures.assignAgent(w.admin(), w.code(), invitedAgent.accountId());
        Session suspendedAgent = fixtures.agent(w.admin(), w.code());
        flow.suspend(w.admin(), suspendedAgent.accountId());

        for (Session staff : List.of(w.admin(), w.agent())) {
            ApiResponse answer = candidates(staff, w.code()).expectStatus(200);
            List<JsonNode> people = items(answer);
            assertThat(people).extracting(n -> n.path("accountId").asLong(-1))
                    .as("candidates of %s for %s", w.code(), staff.role())
                    .contains(w.admin().accountId(), secondAdmin.accountId(), w.agent().accountId())
                    .doesNotContain(suspendedAdmin.accountId(), invitedAdmin.accountId(), invitedAgent.accountId(),
                            suspendedAgent.accountId(), w.unassignedAgent().accountId(), w.reporter().accountId(),
                            w.colleague().accountId())
                    .doesNotHaveDuplicates();
            JsonNode agent = people.stream().filter(n -> n.path("accountId").asLong(-1) == w.agent().accountId())
                    .findFirst().orElseThrow();
            assertThat(agent.propertyNames()).as("field names of a candidate")
                    .containsExactlyInAnyOrder("accountId", "name", "role", "photoUpdatedAt");
            assertThat(text(agent, "name")).isEqualTo(w.agent().name());
            assertThat(text(agent, "role")).isEqualTo("AGENT");
            assertThat(people).filteredOn(n -> n.path("accountId").asLong(-1) == secondAdmin.accountId())
                    .singleElement().satisfies(n -> assertThat(text(n, "role")).isEqualTo("ADMIN"));
            assertThat(answer.text()).as("candidates hold no email").doesNotContainIgnoringCase(w.agent().email());
        }
    }

    @Test
    @DisplayName("S3-AC-27 an agent who is not assigned to the company gets 404 for its candidates, the same body as for a code that does not exist")
    void unassignedAgentGetsNotFoundForCandidates() {
        World w = flow.world();
        candidates(w.admin(), w.code()).expectStatus(200);

        ApiResponse missing = candidates(w.unassignedAgent(), Unique.companyCode());
        ApiResponse unassigned = candidates(w.unassignedAgent(), w.code());

        assertThat(missing.status()).as(missing.request()).isEqualTo(404);
        assertThat(missing.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(unassigned.status()).as(unassigned.request()).isEqualTo(404);
        assertThat(unassigned.body()).as("body of %s, like a missing code", unassigned.request())
                .isEqualTo(missing.body());
        assertThat(unassigned.text()).doesNotContain(w.agent().name());
    }

    // S3-AC-28

    @Test
    @DisplayName("S3-AC-28 the console list and detail carry assignee and assigneeUnavailable: null and false without an assignee, the person once set")
    void listAndDetailCarryTheAssignee() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        assertNoAssignee("the detail", flow.detail(w.agent(), issue.key()));
        assertNoAssignee("the list item", listed(w.agent(), w.code(), issue.key()));

        flow.assign(w.admin(), issue.key(), w.agent().accountId());

        for (JsonNode view : List.of(flow.detail(w.agent(), issue.key()), listed(w.agent(), w.code(), issue.key()))) {
            assertThat(assigneeId(view)).as("assignee in %s", view).isEqualTo(w.agent().accountId());
            assertThat(text(view.path("assignee"), "name")).isEqualTo(w.agent().name());
            assertThat(view.path("assigneeUnavailable").isBoolean()).isTrue();
            assertThat(view.path("assigneeUnavailable").booleanValue()).as("assigneeUnavailable in %s", view).isFalse();
        }
    }

    // S3-AC-30

    @Test
    @DisplayName("S3-AC-30 a CLOSED issue takes an assignee and loses it again, both 200")
    void closedIssueTakesAndLosesAnAssignee() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        flow.close(w.reporter(), issue.key());

        JsonNode assigned = flow.assign(w.agent(), issue.key(), w.agent().accountId());
        JsonNode cleared = flow.unassign(w.agent(), issue.key());

        assertThat(assigneeId(assigned)).isEqualTo(w.agent().accountId());
        assertThat(text(assigned, "status")).isEqualTo("CLOSED");
        assertNoAssignee("the closed issue after clearing", cleared);
        assertThat(text(flow.detail(w.admin(), issue.key()), "status")).isEqualTo("CLOSED");
    }

    // S3-AC-31

    /** How an assignee stops seeing the issue, and how that is undone. */
    enum Loss {
        TAKEN_OFF_THE_COMPANY,
        SUSPENDED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Loss.class)
    @DisplayName("S3-AC-31 an assignee taken off the company or suspended stays the assignee and is marked unavailable; assigned again or reactivated, the mark goes")
    void assigneeWhoLostAccessIsMarkedUnavailable(Loss loss) {
        World w = flow.world();
        Session assignee = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        flow.assign(w.admin(), issue.key(), assignee.accountId());

        if (loss == Loss.SUSPENDED) {
            flow.suspend(w.admin(), assignee.accountId());
        } else {
            flow.unassignAgent(w.admin(), w.code(), assignee.accountId());
        }

        for (JsonNode view : List.of(flow.detail(w.admin(), issue.key()), listed(w.admin(), w.code(), issue.key()))) {
            assertThat(assigneeId(view)).as("assignee kept in %s", view).isEqualTo(assignee.accountId());
            assertThat(view.path("assigneeUnavailable").asBoolean(false)).as("assigneeUnavailable in %s", view)
                    .isTrue();
        }

        if (loss == Loss.SUSPENDED) {
            flow.reactivate(w.admin(), assignee.accountId());
        } else {
            fixtures.assignAgent(w.admin(), w.code(), assignee.accountId());
        }

        for (JsonNode view : List.of(flow.detail(w.admin(), issue.key()), listed(w.admin(), w.code(), issue.key()))) {
            assertThat(assigneeId(view)).as("assignee in %s", view).isEqualTo(assignee.accountId());
            assertThat(view.path("assigneeUnavailable").asBoolean(true)).as("assigneeUnavailable in %s", view)
                    .isFalse();
        }
    }
}
