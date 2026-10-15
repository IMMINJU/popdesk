package io.popdesk.link;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.link.LinkSteps.DUPLICATED_BY;
import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.body;
import static io.popdesk.link.LinkSteps.described;
import static io.popdesk.link.LinkSteps.titleOf;
import static io.popdesk.task.TaskSteps.assertInvalid;
import static io.popdesk.task.TaskSteps.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Linking two issues ({@code POST /api/console/issues/{key}/links}, LINK-1 and LINK-2): the link it makes, issues
 * the caller cannot see, the input, a link already there, duplicate chains, a closed issue, and the order of the
 * checks. A refused request is read again afterwards: neither issue has a new link. Companies A and B and their
 * staff come from {@link LinkSteps#world}.
 */
class LinkCreateTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** Fails unless both issues have no link, read by the admin. */
    private static void assertNoLinks(World w, String... keys) {
        for (String key : keys) {
            assertThat(described(w.admin(), key)).as("links of %s read by the admin", key).isEmpty();
        }
    }

    // S6C-AC-01

    @Test
    @DisplayName("S6C-AC-01 staff who see both issues link an issue of A to an issue of B: 201 with the type, the other issue's key, title, status, company code and name, who linked, and when")
    void linksIssuesOfTwoCompanies() {
        World w = LinkSteps.world(fixtures, flow);
        Issue ofA = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        flow.moveTo(w.admin(), ofB.key(), "IN_PROGRESS");
        Instant now = flow.tick();

        JsonNode link = LinkSteps.link(w.linker(), ofA.key(), ofB.key(), RELATED);

        assertThat(link.path("type").asString(null)).as("type of %s", link).isEqualTo(RELATED);
        JsonNode other = link.path("issue");
        assertThat(other.path("key").asString(null)).as("issue.key").isEqualTo(ofB.key());
        assertThat(other.path("title").asString(null)).as("issue.title").isEqualTo(titleOf(ofB));
        assertThat(other.path("status").asString(null)).as("issue.status").isEqualTo("IN_PROGRESS");
        assertThat(other.path("companyCode").asString(null)).as("issue.companyCode").isEqualTo(w.b().code());
        assertThat(other.path("companyName").asString(null)).as("issue.companyName").isEqualTo(w.b().name());
        assertThat(link.path("createdBy").path("accountId").asLong(-1)).as("createdBy")
                .isEqualTo(w.linker().accountId());
        assertThat(IssueFlow.time(link, "createdAt")).as("createdAt").isEqualTo(now);
        assertThat(LinkSteps.links(w.linker(), ofA.key())).as("links of A's issue read again").containsExactly(link);
        assertThat(described(w.admin(), ofB.key())).as("links of B's issue").containsExactly(RELATED + " " + ofA.key());
    }

    // S6C-AC-02

    /** What the caller cannot see, or what does not exist. */
    enum Unseen {
        OTHER_NOBODY_HAS,
        OTHER_NOT_A_KEY,
        OTHER_OF_B,
        PATH_OF_B
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Unseen.class)
    @DisplayName("S6C-AC-02 an agent of A only, and an agent assigned to nothing, linking with an issue of B (in the path or as issueKey), a key nobody has, or a value that is not a key, get the same 404 body as for a key nobody has, and the admin reads no link on either issue")
    void unseenIssueIsNotFound(Unseen unseen) {
        World w = LinkSteps.world(fixtures, flow);
        Issue ofA = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);

        for (Session caller : List.of(w.agentOfA(), w.unassigned())) {
            String path = caller == w.unassigned() || unseen == Unseen.PATH_OF_B ? ofB.key() : ofA.key();
            String other = switch (unseen) {
                case OTHER_NOBODY_HAS -> NO_KEY;
                case OTHER_NOT_A_KEY -> "not a key!";
                case OTHER_OF_B -> ofB.key();
                case PATH_OF_B -> ofA.key();
            };
            ApiResponse missing = LinkSteps.createRequest(caller, NO_KEY, ofA.key(), RELATED).send();
            ApiResponse answer = LinkSteps.createRequest(caller, path, other, DUPLICATE_OF).send();

            assertRefused(missing, 404, "NOT_FOUND");
            assertRefused(answer, 404, "NOT_FOUND");
            assertThat(answer.text()).as("body of %s, like %s", answer.request(), missing.request())
                    .isEqualTo(missing.text());
        }
        if (unseen == Unseen.OTHER_OF_B) {
            ApiResponse fromA = LinkSteps.createRequest(w.agentOfA(), ofA.key(), ofB.key(), RELATED).send();
            ApiResponse toNobody = LinkSteps.createRequest(w.agentOfA(), ofA.key(), NO_KEY, RELATED).send();
            assertRefused(fromA, 404, "NOT_FOUND");
            assertThat(fromA.text()).as("an issue of B and a key nobody has").isEqualTo(toNobody.text());
        }

        LinkSteps.link(w.linker(), ofA.key(), ofB.key(), RELATED);
        assertThat(described(w.admin(), ofA.key())).as("links of A's issue after the refused requests")
                .containsExactly(RELATED + " " + ofB.key());
    }

    // S6C-AC-03

    /** A body with a missing, null, or blank {@code issueKey}, or a missing, null, or unknown {@code type}. */
    enum BadInput {
        KEY_MISSING("issueKey", "REQUIRED"),
        KEY_NULL("issueKey", "REQUIRED"),
        KEY_EMPTY("issueKey", "REQUIRED"),
        KEY_BLANK("issueKey", "REQUIRED"),
        TYPE_MISSING("type", "REQUIRED"),
        TYPE_NULL("type", "REQUIRED"),
        TYPE_UNKNOWN("type", "UNKNOWN_VALUE");

        final String field;
        final String code;

        BadInput(String field, String code) {
            this.field = field;
            this.code = code;
        }

        Map<String, Object> body(String otherKey) {
            return switch (this) {
                case KEY_MISSING -> LinkSteps.body("type", RELATED);
                case KEY_NULL -> LinkSteps.body("issueKey", null, "type", RELATED);
                case KEY_EMPTY -> LinkSteps.body("issueKey", "", "type", RELATED);
                case KEY_BLANK -> LinkSteps.body("issueKey", "  \t ", "type", RELATED);
                case TYPE_MISSING -> LinkSteps.body("issueKey", otherKey);
                case TYPE_NULL -> LinkSteps.body("issueKey", otherKey, "type", null);
                case TYPE_UNKNOWN -> LinkSteps.body("issueKey", otherKey, "type", "DUPLICATE");
            };
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BadInput.class)
    @DisplayName("S6C-AC-03 a missing, null, empty, or blank issueKey is 400 INVALID_INPUT issueKey REQUIRED; a missing or null type is type REQUIRED, an unknown one type UNKNOWN_VALUE; nothing is linked")
    void badInputIsRefused(BadInput input) {
        World w = LinkSteps.world(fixtures, flow);
        Issue one = w.issueOfA(fixtures);
        Issue other = w.issueOfB(fixtures);

        ApiResponse answer = LinkSteps.createRequest(w.linker(), one.key(), input.body(other.key())).send();

        assertInvalid(answer, input.field, input.code);
        assertNoLinks(w, one.key(), other.key());
        LinkSteps.link(w.linker(), one.key(), other.key(), RELATED);
    }

    @Test
    @DisplayName("S6C-AC-03 linking an issue to itself is 400 LINK_TO_SELF for every type, and the issue has no link")
    void linkToSelfIsRefused() {
        World w = LinkSteps.world(fixtures, flow);
        Issue one = w.issueOfA(fixtures);
        Issue other = w.issueOfA(fixtures);

        for (String type : List.of(DUPLICATE_OF, DUPLICATED_BY, RELATED)) {
            assertRefused(LinkSteps.createRequest(w.linker(), one.key(), one.key(), type).send(), 400,
                    "LINK_TO_SELF");
        }

        LinkSteps.link(w.linker(), one.key(), other.key(), RELATED);
        assertThat(described(w.admin(), one.key())).as("links of the issue").containsExactly(RELATED + " " + other.key());
    }

    // S6C-AC-04

    /** A second link between the same two issues: from which side, and of which type. */
    enum Again {
        SAME_SIDE_SAME_TYPE(false, DUPLICATE_OF),
        SAME_SIDE_RELATED(false, RELATED),
        SAME_SIDE_REVERSED(false, DUPLICATED_BY),
        OTHER_SIDE_RELATED(true, RELATED),
        OTHER_SIDE_SAME_LINK(true, DUPLICATED_BY),
        OTHER_SIDE_REVERSED(true, DUPLICATE_OF);

        final boolean fromOther;
        final String type;

        Again(boolean fromOther, String type) {
            this.fromOther = fromOther;
            this.type = type;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Again.class)
    @DisplayName("S6C-AC-04 with a link between two issues, another link between them of any type and from either side is 409 LINK_EXISTS, and the one link stays as it was")
    void secondLinkIsRefused(Again again) {
        World w = LinkSteps.world(fixtures, flow);
        Issue duplicate = w.issueOfA(fixtures);
        Issue original = w.issueOfB(fixtures);
        LinkSteps.link(w.linker(), duplicate.key(), original.key(), DUPLICATE_OF);
        List<JsonNode> before = LinkSteps.links(w.admin(), duplicate.key());

        ApiResponse answer = again.fromOther
                ? LinkSteps.createRequest(w.linker(), original.key(), duplicate.key(), again.type).send()
                : LinkSteps.createRequest(w.linker(), duplicate.key(), original.key(), again.type).send();

        assertRefused(answer, 409, "LINK_EXISTS");
        assertThat(LinkSteps.links(w.admin(), duplicate.key())).as("links of the duplicate").isEqualTo(before);
        assertThat(described(w.admin(), original.key())).as("links of the original")
                .containsExactly(DUPLICATED_BY + " " + duplicate.key());
    }

    // S6C-AC-05

    /** What is there before a duplicate link between D (to be the duplicate) and O (to be the original). */
    enum Chain {
        ORIGINAL_IS_A_DUPLICATE,
        DUPLICATE_IS_AN_ORIGINAL,
        DUPLICATE_HAS_AN_ORIGINAL
    }

    /** How the new duplicate link is asked for. */
    enum Asked {
        FROM_THE_DUPLICATE,
        FROM_THE_ORIGINAL
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Chain.class)
    @DisplayName("S6C-AC-05 a duplicate link whose original is already a duplicate, whose duplicate is already an original, or whose duplicate already has an original is 400 LINK_DUPLICATE_CHAIN, asked from either side; RELATED between the same two issues is 201")
    void duplicateChainIsRefused(Chain chain) {
        for (Asked asked : Asked.values()) {
            World w = LinkSteps.world(fixtures, flow);
            Issue d = w.issueOfA(fixtures);
            Issue o = w.issueOfB(fixtures);
            Issue third = w.issueOfA(fixtures);
            switch (chain) {
                case ORIGINAL_IS_A_DUPLICATE -> LinkSteps.link(w.linker(), o.key(), third.key(), DUPLICATE_OF);
                case DUPLICATE_IS_AN_ORIGINAL -> LinkSteps.link(w.linker(), third.key(), d.key(), DUPLICATE_OF);
                case DUPLICATE_HAS_AN_ORIGINAL -> LinkSteps.link(w.linker(), d.key(), third.key(), DUPLICATE_OF);
            }

            ApiResponse answer = asked == Asked.FROM_THE_DUPLICATE
                    ? LinkSteps.createRequest(w.linker(), d.key(), o.key(), DUPLICATE_OF).send()
                    : LinkSteps.createRequest(w.linker(), o.key(), d.key(), DUPLICATED_BY).send();

            assertRefused(answer, 400, "LINK_DUPLICATE_CHAIN");
            assertThat(LinkSteps.linkedKeys(w.admin(), d.key())).as("%s %s: links of D", chain, asked)
                    .doesNotContain(o.key());
            assertThat(LinkSteps.linkedKeys(w.admin(), o.key())).as("%s %s: links of O", chain, asked)
                    .doesNotContain(d.key());
            LinkSteps.link(w.linker(), d.key(), o.key(), RELATED);
            assertThat(described(w.admin(), d.key())).as("%s %s: links of D after RELATED", chain, asked)
                    .contains(RELATED + " " + o.key());
        }
    }

    // S6C-AC-07

    @Test
    @DisplayName("S6C-AC-07 when the issue in the path is CLOSED, linking and removing a link are 400 ISSUE_CLOSED and change nothing; a CLOSED other issue can be linked")
    void closedIssueInThePath() {
        World w = LinkSteps.world(fixtures, flow);
        Issue closed = w.issueOfA(fixtures);
        Issue linked = w.issueOfB(fixtures);
        Issue fresh = w.issueOfB(fixtures);
        Issue newer = w.issueOfA(fixtures);
        LinkSteps.link(w.linker(), closed.key(), linked.key(), RELATED);
        flow.changeStatus(w.admin(), closed.key(), "CLOSED");

        ApiResponse link = LinkSteps.createRequest(w.linker(), closed.key(), fresh.key(), RELATED).send();
        ApiResponse unlink = LinkSteps.deleteRequest(w.linker(), closed.key(), linked.key()).send();

        assertRefused(link, 400, "ISSUE_CLOSED");
        assertRefused(unlink, 400, "ISSUE_CLOSED");
        assertThat(described(w.admin(), closed.key())).as("links of the closed issue")
                .containsExactly(RELATED + " " + linked.key());
        assertNoLinks(w, fresh.key());
        JsonNode toClosed = LinkSteps.link(w.linker(), newer.key(), closed.key(), DUPLICATE_OF);
        assertThat(toClosed.path("issue").path("status").asString(null)).as("status of the closed original")
                .isEqualTo("CLOSED");
        assertThat(described(w.admin(), closed.key())).as("links of the closed issue after the newer one")
                .containsExactly(RELATED + " " + linked.key(), DUPLICATED_BY + " " + newer.key());
    }

    // S6C-AC-30

    @Test
    @DisplayName("S6C-AC-30 linking as a duplicate, from either side, changes the status of neither issue")
    void duplicateLinkKeepsTheStatus() {
        World w = LinkSteps.world(fixtures, flow);
        Issue duplicate = w.issueOfA(fixtures);
        Issue original = w.issueOfB(fixtures);
        Issue another = w.issueOfA(fixtures);
        flow.moveTo(w.admin(), original.key(), "RESOLVED");
        flow.moveTo(w.admin(), another.key(), "IN_PROGRESS");

        LinkSteps.link(w.linker(), duplicate.key(), original.key(), DUPLICATE_OF);
        LinkSteps.link(w.linker(), original.key(), another.key(), DUPLICATED_BY);

        assertThat(flow.detail(w.admin(), duplicate.key()).path("status").asString(null)).as("the duplicate")
                .isEqualTo("RECEIVED");
        assertThat(flow.detail(w.admin(), original.key()).path("status").asString(null)).as("the original")
                .isEqualTo("RESOLVED");
        assertThat(flow.detail(w.admin(), another.key()).path("status").asString(null)).as("the other duplicate")
                .isEqualTo("IN_PROGRESS");
    }

    // S6C-AC-14

    /** Two things wrong with one request; the check that comes first answers. */
    enum Pair {
        UNSEEN_OTHER_AND_WRONG_TYPE,
        UNSEEN_PATH_AND_NO_KEY,
        NO_KEY_AND_WRONG_TYPE,
        SELF_AND_WRONG_TYPE,
        SELF_AND_CLOSED,
        CLOSED_AND_LINKED,
        LINKED_AND_CHAIN,
        WRONG_TYPE_AND_CLOSED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Pair.class)
    @DisplayName("S6C-AC-14 with two things wrong, linking answers for the check that comes first: an unseen other issue before the type (404), an unseen path before issueKey (404), issueKey before the type, the type before LINK_TO_SELF, LINK_TO_SELF before ISSUE_CLOSED, ISSUE_CLOSED before LINK_EXISTS, LINK_EXISTS before LINK_DUPLICATE_CHAIN, the type before ISSUE_CLOSED")
    void linkChecksInOrder(Pair pair) {
        World w = LinkSteps.world(fixtures, flow);
        Issue one = w.issueOfA(fixtures);
        Issue other = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        Session agent = w.agentOfA();
        ApiResponse answer = switch (pair) {
            case UNSEEN_OTHER_AND_WRONG_TYPE ->
                    LinkSteps.createRequest(agent, one.key(), body("issueKey", ofB.key(), "type", "WRONG")).send();
            case UNSEEN_PATH_AND_NO_KEY -> LinkSteps.createRequest(agent, ofB.key(), body("type", RELATED)).send();
            case NO_KEY_AND_WRONG_TYPE -> LinkSteps.createRequest(agent, one.key(), body("type", "WRONG")).send();
            case SELF_AND_WRONG_TYPE ->
                    LinkSteps.createRequest(agent, one.key(), body("issueKey", one.key(), "type", "WRONG")).send();
            case SELF_AND_CLOSED -> {
                flow.changeStatus(w.admin(), one.key(), "CLOSED");
                yield LinkSteps.createRequest(agent, one.key(), one.key(), RELATED).send();
            }
            case CLOSED_AND_LINKED -> {
                LinkSteps.link(w.linker(), one.key(), other.key(), RELATED);
                flow.changeStatus(w.admin(), one.key(), "CLOSED");
                yield LinkSteps.createRequest(agent, one.key(), other.key(), RELATED).send();
            }
            case LINKED_AND_CHAIN -> {
                // one is a duplicate of other; other as a duplicate of one would also be a chain
                LinkSteps.link(w.linker(), one.key(), other.key(), DUPLICATE_OF);
                yield LinkSteps.createRequest(agent, other.key(), one.key(), DUPLICATE_OF).send();
            }
            case WRONG_TYPE_AND_CLOSED -> {
                flow.changeStatus(w.admin(), one.key(), "CLOSED");
                yield LinkSteps.createRequest(agent, one.key(), body("issueKey", other.key(), "type", "WRONG"))
                        .send();
            }
        };

        switch (pair) {
            case UNSEEN_OTHER_AND_WRONG_TYPE, UNSEEN_PATH_AND_NO_KEY -> assertRefused(answer, 404, "NOT_FOUND");
            case NO_KEY_AND_WRONG_TYPE -> assertInvalid(answer, "issueKey", "REQUIRED");
            case SELF_AND_WRONG_TYPE, WRONG_TYPE_AND_CLOSED -> assertInvalid(answer, "type", "UNKNOWN_VALUE");
            case SELF_AND_CLOSED -> assertRefused(answer, 400, "LINK_TO_SELF");
            case CLOSED_AND_LINKED -> assertRefused(answer, 400, "ISSUE_CLOSED");
            case LINKED_AND_CHAIN -> assertRefused(answer, 409, "LINK_EXISTS");
        }
        assertThat(LinkSteps.linkedKeys(w.admin(), ofB.key())).as("links of B's issue").isEmpty();
        LinkSteps.link(w.linker(), other.key(), ofB.key(), RELATED);
    }

    @Test
    @DisplayName("S6C-AC-14 removing a link checks this issue, the other issue, the link, then CLOSED: on a CLOSED issue an unseen other issue and a missing link are 404, not ISSUE_CLOSED")
    void unlinkChecksInOrder() {
        World w = LinkSteps.world(fixtures, flow);
        Issue closed = w.issueOfA(fixtures);
        Issue linked = w.issueOfA(fixtures);
        Issue unlinked = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        LinkSteps.link(w.linker(), closed.key(), linked.key(), RELATED);
        LinkSteps.link(w.linker(), closed.key(), ofB.key(), RELATED);
        flow.changeStatus(w.admin(), closed.key(), "CLOSED");

        ApiResponse unseen = LinkSteps.deleteRequest(w.agentOfA(), closed.key(), ofB.key()).send();
        ApiResponse nobody = LinkSteps.deleteRequest(w.agentOfA(), closed.key(), NO_KEY).send();
        ApiResponse noLink = LinkSteps.deleteRequest(w.agentOfA(), closed.key(), unlinked.key()).send();
        ApiResponse closedOne = LinkSteps.deleteRequest(w.agentOfA(), closed.key(), linked.key()).send();

        assertRefused(unseen, 404, "NOT_FOUND");
        assertThat(unseen.text()).as("an unseen other issue and a key nobody has").isEqualTo(nobody.text());
        assertRefused(noLink, 404, "NOT_FOUND");
        assertRefused(closedOne, 400, "ISSUE_CLOSED");
        assertThat(described(w.admin(), closed.key())).as("links of the closed issue")
                .containsExactly(RELATED + " " + linked.key(), RELATED + " " + ofB.key());
    }
}
