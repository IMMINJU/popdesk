package io.popdesk.link;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.link.LinkSteps.DUPLICATED_BY;
import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.described;
import static io.popdesk.link.LinkSteps.titleOf;
import static io.popdesk.task.TaskSteps.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Removing a link ({@code DELETE /api/console/issues/{key}/links/{otherKey}}) and the links of an issue
 * ({@code GET .../links}, LINK-1 and LINK-4): from either side, what the caller cannot see, and the type seen from
 * each side. The agent of A only must not see a link to B, in the items or in their number (TM-41).
 */
class LinkListTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    // S6C-AC-10

    @ParameterizedTest(name = "from the {0}")
    @EnumSource(Side.class)
    @DisplayName("S6C-AC-10 removing a link from either issue is 204, neither issue lists it afterwards, and the two issues can be linked again")
    void removesFromEitherSide(Side from) {
        World w = LinkSteps.world(fixtures, flow);
        Issue duplicate = w.issueOfA(fixtures);
        Issue original = w.issueOfB(fixtures);
        Issue kept = w.issueOfA(fixtures);
        LinkSteps.link(w.linker(), duplicate.key(), original.key(), DUPLICATE_OF);
        LinkSteps.link(w.linker(), duplicate.key(), kept.key(), RELATED);

        if (from == Side.DUPLICATE) {
            LinkSteps.unlink(w.linker(), duplicate.key(), original.key());
        } else {
            LinkSteps.unlink(w.linker(), original.key(), duplicate.key());
        }

        assertThat(described(w.admin(), duplicate.key())).as("links of the duplicate")
                .containsExactly(RELATED + " " + kept.key());
        assertThat(described(w.admin(), original.key())).as("links of the original").isEmpty();
        LinkSteps.link(w.linker(), original.key(), duplicate.key(), RELATED);
        assertThat(described(w.admin(), duplicate.key())).as("links of the duplicate after linking again")
                .containsExactly(RELATED + " " + kept.key(), RELATED + " " + original.key());
    }

    enum Side {
        DUPLICATE,
        ORIGINAL
    }

    // S6C-AC-11

    /** Why the agent cannot remove the link. */
    enum NotThere {
        OTHER_COMPANY_LEFT_AFTER_LINKING,
        OTHER_NEVER_ASSIGNED,
        NO_LINK_BETWEEN_THEM
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NotThere.class)
    @DisplayName("S6C-AC-11 removing a link to an issue the agent cannot see (also after being taken off its company after linking) is the 404 of a key nobody has, as is removing a link that is not there, and the links stay")
    void unseenOrMissingLinkIsNotRemoved(NotThere why) {
        World w = LinkSteps.world(fixtures, flow);
        Issue ofA = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        Issue otherOfA = w.issueOfA(fixtures);
        LinkSteps.link(w.linker(), ofA.key(), ofB.key(), RELATED);
        var caller = why == NotThere.OTHER_NEVER_ASSIGNED ? w.agentOfA() : w.linker();
        String other = why == NotThere.NO_LINK_BETWEEN_THEM ? otherOfA.key() : ofB.key();
        if (why == NotThere.OTHER_COMPANY_LEFT_AFTER_LINKING) {
            flow.unassignAgent(w.admin(), w.b().code(), w.linker().accountId());
        }

        ApiResponse answer = LinkSteps.deleteRequest(caller, ofA.key(), other).send();
        ApiResponse nobody = LinkSteps.deleteRequest(caller, ofA.key(), NO_KEY).send();

        assertRefused(answer, 404, "NOT_FOUND");
        assertThat(answer.text()).as("body of %s, like %s", answer.request(), nobody.request())
                .isEqualTo(nobody.text());
        assertThat(described(w.admin(), ofA.key())).as("links of A's issue").containsExactly(RELATED + " " + ofB.key());
        assertThat(described(w.admin(), ofB.key())).as("links of B's issue").containsExactly(RELATED + " " + ofA.key());
    }

    // S6C-AC-12

    @Test
    @DisplayName("S6C-AC-12 the agent of A only lists only the links to issues of A, in the order they were made, with no trace of B's issue; B's issue's links are 404; once assigned to B the link to B is listed again")
    void unseenLinksAreLeftOut() {
        World w = LinkSteps.world(fixtures, flow);
        Issue hub = w.issueOfA(fixtures);
        Issue first = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        Issue last = w.issueOfA(fixtures);
        LinkSteps.link(w.linker(), hub.key(), first.key(), RELATED);
        LinkSteps.link(w.linker(), hub.key(), ofB.key(), DUPLICATED_BY);
        LinkSteps.link(w.linker(), last.key(), hub.key(), DUPLICATE_OF);

        ApiResponse seenByA = LinkSteps.list(w.agentOfA(), hub.key());
        ApiResponse ofBByA = LinkSteps.listRequest(w.agentOfA(), ofB.key()).send();
        ApiResponse nobody = LinkSteps.listRequest(w.agentOfA(), NO_KEY).send();

        assertThat(LinkSteps.linkedKeys(w.agentOfA(), hub.key())).as("links the agent of A sees")
                .containsExactly(first.key(), last.key());
        assertThat(seenByA.text()).as("the list of the agent of A").doesNotContain(ofB.key())
                .doesNotContain(titleOf(ofB));
        assertThat(seenByA.json().propertyNames()).as("fields of the list").containsExactly("items");
        assertRefused(ofBByA, 404, "NOT_FOUND");
        assertThat(ofBByA.text()).as("B's issue's links, like a key nobody has").isEqualTo(nobody.text());
        assertThat(described(w.admin(), hub.key())).as("links the admin sees").containsExactly(
                RELATED + " " + first.key(), DUPLICATED_BY + " " + ofB.key(), DUPLICATED_BY + " " + last.key());

        fixtures.assignAgent(w.admin(), w.b().code(), w.agentOfA().accountId());

        assertThat(LinkSteps.linkedKeys(w.agentOfA(), hub.key())).as("links once assigned to B")
                .containsExactly(first.key(), ofB.key(), last.key());
    }

    // S6C-AC-13

    /** How a duplicate link is asked for, and from which issue. */
    enum Asked {
        DUPLICATE_OF_FROM_THE_DUPLICATE,
        DUPLICATED_BY_FROM_THE_ORIGINAL,
        RELATED_FROM_EITHER
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Asked.class)
    @DisplayName("S6C-AC-13 the type is seen from each issue: the duplicate lists DUPLICATE_OF the original and the original DUPLICATED_BY the duplicate, whichever side linked them; RELATED is RELATED on both")
    void typeIsSeenFromEachSide(Asked asked) {
        World w = LinkSteps.world(fixtures, flow);
        Issue duplicate = w.issueOfA(fixtures);
        Issue original = w.issueOfB(fixtures);

        String answered = switch (asked) {
            case DUPLICATE_OF_FROM_THE_DUPLICATE ->
                    LinkSteps.describe(LinkSteps.link(w.linker(), duplicate.key(), original.key(), DUPLICATE_OF));
            case DUPLICATED_BY_FROM_THE_ORIGINAL ->
                    LinkSteps.describe(LinkSteps.link(w.linker(), original.key(), duplicate.key(), DUPLICATED_BY));
            case RELATED_FROM_EITHER ->
                    LinkSteps.describe(LinkSteps.link(w.linker(), original.key(), duplicate.key(), RELATED));
        };

        boolean related = asked == Asked.RELATED_FROM_EITHER;
        List<String> ofDuplicate = described(w.admin(), duplicate.key());
        List<String> ofOriginal = described(w.admin(), original.key());
        assertThat(ofDuplicate).as("links of the duplicate")
                .containsExactly((related ? RELATED : DUPLICATE_OF) + " " + original.key());
        assertThat(ofOriginal).as("links of the original")
                .containsExactly((related ? RELATED : DUPLICATED_BY) + " " + duplicate.key());
        assertThat(answered).as("the 201 answer, seen from the path's issue")
                .isEqualTo(asked == Asked.DUPLICATE_OF_FROM_THE_DUPLICATE ? ofDuplicate.getFirst()
                        : ofOriginal.getFirst());
    }
}
