package io.popdesk.link;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.LIMIT;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.described;
import static io.popdesk.task.TaskSteps.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The 50 links of an issue (S6C-AC-06, TM-59), counted with the links the caller cannot see, and the limit of 60
 * links and removals per account in 60 seconds (S6C-AC-09), which counts refused requests too. Links are made
 * through the API ({@code acceptance.md} 4절): the issues are filed over several windows of the filing limit.
 */
class LinkLimitTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** An issue of A with 50 links: first a duplicate of another issue of A, one to an issue of B, the rest to A. */
    private record Full(Issue hub, List<Issue> ofA, Issue ofB) {}

    private Full full(World w) {
        List<Issue> issues = LinkSteps.fileMany(fixtures, clock, w.reporterA(), w.a().code(), LIMIT + 2);
        Issue hub = issues.getFirst();
        List<Issue> ofA = issues.subList(1, LIMIT);
        Issue ofB = w.issueOfB(fixtures);
        LinkSteps.link(w.linker(), hub.key(), ofA.getFirst().key(), DUPLICATE_OF);
        LinkSteps.link(w.linker(), hub.key(), ofB.key(), RELATED);
        for (Issue each : ofA.subList(1, ofA.size())) {
            LinkSteps.link(w.linker(), each.key(), hub.key(), RELATED);
        }
        return new Full(hub, ofA, ofB);
    }

    // S6C-AC-06

    /** Which issue of the new link has 50 already. */
    enum Full50 {
        PATH,
        OTHER
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Full50.class)
    @DisplayName("S6C-AC-06 with 50 links on an issue, one of them to B, a new link from it or to it by the agent of A only (who lists 49) is 400 LINK_LIMIT_EXCEEDED and changes nothing")
    void fiftyFirstLinkIsRefused(Full50 which) {
        World w = LinkSteps.world(fixtures, flow);
        Full f = full(w);
        Issue fresh = w.issueOfA(fixtures);
        List<String> before = described(w.admin(), f.hub().key());

        ApiResponse answer = which == Full50.PATH
                ? LinkSteps.createRequest(w.agentOfA(), f.hub().key(), fresh.key(), RELATED).send()
                : LinkSteps.createRequest(w.agentOfA(), fresh.key(), f.hub().key(), RELATED).send();

        assertThat(before).as("links of the full issue").hasSize(LIMIT);
        assertThat(LinkSteps.links(w.agentOfA(), f.hub().key())).as("links the agent of A sees").hasSize(LIMIT - 1);
        assertRefused(answer, 400, "LINK_LIMIT_EXCEEDED");
        assertThat(described(w.admin(), f.hub().key())).as("links of the full issue afterwards").isEqualTo(before);
        assertThat(described(w.admin(), fresh.key())).as("links of the new issue").isEmpty();
    }

    @Test
    @DisplayName("S6C-AC-14 on an issue with 50 links that is already a duplicate, a duplicate link from it is LINK_DUPLICATE_CHAIN, not LINK_LIMIT_EXCEEDED")
    void chainBeforeLimit() {
        World w = LinkSteps.world(fixtures, flow);
        Full f = full(w);
        Issue fresh = w.issueOfA(fixtures);

        ApiResponse answer = LinkSteps.createRequest(w.agentOfA(), f.hub().key(), fresh.key(), DUPLICATE_OF).send();

        assertRefused(answer, 400, "LINK_DUPLICATE_CHAIN");
        assertThat(described(w.admin(), fresh.key())).as("links of the new issue").isEmpty();
    }

    // S6C-AC-09

    @Test
    @DisplayName("S6C-AC-09 S6C-AC-14 after 60 link and removal requests by one account within 60 seconds, 201, 204, 404 (also for a path issue of a company it is not assigned to), 400, and 409 alike, the 61st link or removal is 429 RATE_LIMITED with Retry-After before any other check, also on such a path issue, and changes nothing; another account is not limited")
    void sixtyFirstRequestIsLimited() {
        World w = LinkSteps.world(fixtures, flow);
        Issue one = w.issueOfA(fixtures);
        Issue two = w.issueOfA(fixtures);
        Issue three = w.issueOfA(fixtures);
        Company c = fixtures.createCompany(w.admin());
        Issue unseen = fixtures.fileIssue(fixtures.customer(w.admin(), c.code()), c.code());
        LinkSteps.nextWindow(clock);

        LinkSteps.link(w.linker(), one.key(), two.key(), RELATED);
        for (int i = 0; i < 9; i++) {
            LinkSteps.createRequest(w.linker(), one.key(), two.key(), RELATED).expect(409);
        }
        for (int i = 0; i < 5; i++) {
            LinkSteps.createRequest(w.linker(), one.key(), NO_KEY, RELATED).expect(404);
            LinkSteps.createRequest(w.linker(), unseen.key(), one.key(), RELATED).expect(404);
        }
        for (int i = 0; i < 10; i++) {
            LinkSteps.createRequest(w.linker(), one.key(), one.key(), RELATED).expect(400);
        }
        for (int i = 0; i < 5; i++) {
            LinkSteps.deleteRequest(w.linker(), one.key(), three.key()).expect(404);
            LinkSteps.deleteRequest(w.linker(), unseen.key(), one.key()).expect(404);
        }
        for (int i = 0; i < 10; i++) {
            LinkSteps.unlink(w.linker(), one.key(), two.key());
            LinkSteps.link(w.linker(), one.key(), two.key(), RELATED);
        }

        List<ApiResponse> limited = List.of(
                LinkSteps.createRequest(w.linker(), one.key(), three.key(), RELATED).send(),
                LinkSteps.createRequest(w.linker(), one.key(), NO_KEY, "WRONG").send(),
                LinkSteps.createRequest(w.linker(), unseen.key(), one.key(), RELATED).send(),
                LinkSteps.deleteRequest(w.linker(), one.key(), two.key()).send(),
                LinkSteps.deleteRequest(w.linker(), unseen.key(), two.key()).send());

        for (ApiResponse answer : limited) {
            assertRefused(answer, 429, "RATE_LIMITED");
            assertThat(answer.header("Retry-After")).as("Retry-After of %s", answer.request()).isNotBlank();
        }
        assertThat(described(w.admin(), one.key())).as("links after the limited requests")
                .containsExactly(RELATED + " " + two.key());
        assertThat(described(w.admin(), unseen.key())).as("links of the issue the linker cannot see").isEmpty();
        LinkSteps.link(w.agentOfA(), one.key(), three.key(), RELATED);
        LinkSteps.nextWindow(clock);
        LinkSteps.unlink(w.linker(), one.key(), two.key());
    }
}
