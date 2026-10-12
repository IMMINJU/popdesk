package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.text;
import static io.popdesk.issue.TalkFlow.accountIds;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The people staff can mention in a comment on one issue, for a public and for an internal comment,
 * and that an agent who cannot see the issue learns nothing from asking.
 *
 * <p>Other tests leave admins in the same database, so the lists are checked for the people this
 * test made, never for being exactly them.
 */
class MentionCandidatesTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    // S4-AC-22

    @Test
    @DisplayName("S4-AC-22 the candidates are the active admins, the assigned active agents, and for a public comment the reporter, with account ID, name, and role and without the caller, other customers, agents who are suspended, invited, or not assigned, or any email")
    void candidatesForPublicAndInternalComments() {
        World w = flow.world();
        Session secondAdmin = fixtures.admin(w.admin());
        Session otherAgent = fixtures.agent(w.admin(), w.code());
        Session suspendedAdmin = fixtures.admin(w.admin());
        flow.suspend(w.admin(), suspendedAdmin.accountId());
        Invitation invitedAgent = fixtures.invite(w.admin(), Role.AGENT);
        fixtures.assignAgent(w.admin(), w.code(), invitedAgent.accountId());
        Session suspendedAgent = fixtures.agent(w.admin(), w.code());
        flow.suspend(w.admin(), suspendedAgent.accountId());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        List<Session> staff = List.of(w.admin(), secondAdmin, w.agent(), otherAgent);

        for (Session caller : List.of(w.agent(), secondAdmin)) {
            for (boolean internal : List.of(false, true)) {
                ApiResponse answer = TalkFlow.candidatesRequest(caller, issue.key(), internal).expect(200);
                JsonNode people = answer.json().path("items");
                String what = "candidates for " + caller.role() + ", internal " + internal;
                List<Long> expected = new ArrayList<>(staff.stream().filter(s -> s != caller)
                        .map(Session::accountId).toList());
                if (!internal) {
                    expected.add(w.reporter().accountId());
                }
                assertThat(accountIds(people)).as(what)
                        .containsAll(expected)
                        .doesNotContain(caller.accountId(), suspendedAdmin.accountId(), invitedAgent.accountId(),
                                suspendedAgent.accountId(), w.unassignedAgent().accountId(),
                                w.agentElsewhere().accountId(), w.colleague().accountId())
                        .doesNotHaveDuplicates();
                if (internal) {
                    assertThat(accountIds(people)).as("%s: the reporter", what)
                            .doesNotContain(w.reporter().accountId());
                } else {
                    JsonNode reporter = TalkFlow.person(people, w.reporter().accountId());
                    assertThat(text(reporter, "name")).isEqualTo(w.reporter().name());
                    assertThat(text(reporter, "role")).isEqualTo("CUSTOMER");
                }
                JsonNode agent = TalkFlow.person(people, otherAgent.accountId());
                assertThat(text(agent, "name")).as("%s: name of the other agent", what).isEqualTo(otherAgent.name());
                assertThat(text(agent, "role")).isEqualTo("AGENT");
                assertThat(text(TalkFlow.person(people, w.admin().accountId()), "role")).isEqualTo("ADMIN");
                for (Session person : List.of(w.admin(), secondAdmin, w.agent(), otherAgent, w.reporter())) {
                    assertThat(answer.text()).as("%s: email of %s", what, person.role())
                            .doesNotContainIgnoringCase(person.email());
                }
                assertThat(people.values()).as("%s: no candidate has an email field", what)
                        .noneMatch(p -> p.has("email"));
            }
        }
    }

    @Test
    @DisplayName("S4-AC-22 without the internal parameter the candidates are those for a public comment")
    void candidatesAreForAPublicCommentByDefault() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        ApiResponse byDefault = w.agent().client().get("/api/console/issues/" + issue.key() + "/mention-candidates")
                .expect(200);

        assertThat(accountIds(byDefault.json().path("items"))).as("candidates without the parameter")
                .contains(w.reporter().accountId())
                .containsExactlyInAnyOrderElementsOf(accountIds(TalkFlow.candidates(w.agent(), issue.key(), false)));
    }

    @Test
    @DisplayName("S4-AC-22 a suspended reporter is no candidate for a public comment either")
    void suspendedReporterIsNoCandidate() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        assertThat(accountIds(TalkFlow.candidates(w.agent(), issue.key(), false))).as("candidates before")
                .contains(w.reporter().accountId(), w.admin().accountId());

        flow.suspend(w.admin(), w.reporter().accountId());

        assertThat(accountIds(TalkFlow.candidates(w.agent(), issue.key(), false))).as("candidates after")
                .contains(w.admin().accountId())
                .doesNotContain(w.reporter().accountId());
    }

    // S4-AC-23

    @Test
    @DisplayName("S4-AC-23 an agent who is not assigned to the issue's company gets 404 for its candidates, the same body as for a key nobody has, without the reporter's name")
    void agentWhoCannotSeeTheIssueGetsNotFound() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        assertThat(accountIds(TalkFlow.candidates(w.agent(), issue.key(), false))).as("candidates for the assigned agent")
                .contains(w.reporter().accountId());

        for (Session caller : List.of(w.unassignedAgent(), w.agentElsewhere())) {
            for (boolean internal : List.of(false, true)) {
                ApiResponse missing = TalkFlow.candidatesRequest(caller, IssueFlow.NO_KEY, internal).send();
                ApiResponse hidden = TalkFlow.candidatesRequest(caller, issue.key(), internal).send();

                assertThat(missing.status()).as(missing.request() + ": " + missing.snippet()).isEqualTo(404);
                assertThat(missing.errorCode()).isEqualTo("NOT_FOUND");
                assertThat(hidden.status()).as(hidden.request() + ": " + hidden.snippet()).isEqualTo(404);
                assertThat(hidden.text()).as("body of %s, like for a key nobody has", hidden.request())
                        .isEqualTo(missing.text());
                assertThat(hidden.text()).doesNotContain(w.reporter().name());
            }
        }
    }
}
