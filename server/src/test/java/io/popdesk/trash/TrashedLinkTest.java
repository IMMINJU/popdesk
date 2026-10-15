package io.popdesk.trash;

import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.LIMIT;
import static io.popdesk.link.LinkSteps.RELATED;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The links of an issue in the trash (S7A-AC-05, LINK-2, LINK-7): nobody can see the issue, so its links are left
 * out of the other issue's links and history, like the links to an issue the reader cannot see. They still count:
 * an issue whose 50 links include one to an issue in the trash takes no 51st, and a duplicate whose original is in
 * the trash is still a duplicate.
 */
class TrashedLinkTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The keys the {@code LINK} entries of the issue's history name, old and new, as the admin reads them. */
    private static List<String> keysInLinkEntries(World w, String key) {
        return LinkSteps.linkEntries(w.admin(), key).stream()
                .flatMap(e -> Stream.of(e.path("oldLink"), e.path("newLink")))
                .filter(link -> link != null && !link.isNull() && !link.isMissingNode())
                .map(link -> link.path("issue").path("key").asString(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Test
    @DisplayName("S7A-AC-05 once an issue is in the trash, the admin reads no link to it among the other issue's links and no LINK entry about it in that issue's history; the link to a third issue is still there")
    void linksToTheTrashAreHidden() {
        World w = LinkSteps.world(fixtures, flow);
        Issue hub = w.issueOfA(fixtures);
        Issue gone = w.issueOfA(fixtures);
        Issue kept = w.issueOfB(fixtures);
        LinkSteps.link(w.linker(), hub.key(), gone.key(), RELATED);
        LinkSteps.link(w.linker(), hub.key(), kept.key(), DUPLICATE_OF);
        List<String> linkedBefore = LinkSteps.linkedKeys(w.admin(), hub.key());
        List<String> inHistoryBefore = keysInLinkEntries(w, hub.key());

        TrashSteps.delete(w.admin(), gone.key());

        assertThat(linkedBefore).as("links of the hub before the delete").containsExactlyInAnyOrder(gone.key(),
                kept.key());
        assertThat(inHistoryBefore).as("keys in the hub's LINK entries before the delete").contains(gone.key(),
                kept.key());
        assertThat(LinkSteps.described(w.admin(), hub.key())).as("links of the hub after the delete")
                .containsExactly(DUPLICATE_OF + " " + kept.key());
        assertThat(LinkSteps.links(w.linker(), hub.key())).as("links of the hub as the linker reads them")
                .extracting(l -> l.path("issue").path("key").asString(null)).containsExactly(kept.key());
        assertThat(keysInLinkEntries(w, hub.key())).as("keys in the hub's LINK entries after the delete")
                .contains(kept.key())
                .doesNotContain(gone.key());
    }

    @Test
    @DisplayName("S7A-AC-05 an issue with 50 links, one of them to an issue in the trash, lists 49 and still takes no new link: 400 LINK_LIMIT_EXCEEDED, nothing linked")
    void hiddenLinkCountsTowardsTheLimit() {
        World w = LinkSteps.world(fixtures, flow);
        List<Issue> issues = LinkSteps.fileMany(fixtures, clock, w.reporterA(), w.a().code(), LIMIT + 2);
        Issue hub = issues.getFirst();
        List<Issue> linked = issues.subList(1, LIMIT + 1);
        Issue fresh = issues.getLast();
        for (int i = 0; i < linked.size(); i++) {
            if (i > 0 && i % 50 == 0) {
                LinkSteps.nextWindow(clock);
            }
            LinkSteps.link(w.linker(), linked.get(i).key(), hub.key(), RELATED);
        }
        LinkSteps.nextWindow(clock);

        TrashSteps.delete(w.admin(), linked.getFirst().key());
        ApiResponse answer = LinkSteps.createRequest(w.linker(), hub.key(), fresh.key(), RELATED).send();

        assertThat(LinkSteps.links(w.admin(), hub.key())).as("links of the hub after the delete").hasSize(LIMIT - 1);
        assertThat(answer.status()).as("a 51st link: " + answer.snippet()).isEqualTo(400);
        assertThat(answer.errorCode()).as("code of the 51st link").isEqualTo("LINK_LIMIT_EXCEEDED");
        assertThat(LinkSteps.links(w.admin(), fresh.key())).as("links of the new issue").isEmpty();
    }

    @Test
    @DisplayName("S7A-AC-05 a duplicate whose original is in the trash still has an original: linking it as a duplicate of a third issue is 400 LINK_DUPLICATE_CHAIN, nothing linked")
    void hiddenLinkCountsInTheChainCheck() {
        World w = LinkSteps.world(fixtures, flow);
        Issue duplicate = w.issueOfA(fixtures);
        Issue original = w.issueOfA(fixtures);
        Issue third = w.issueOfA(fixtures);
        JsonNode link = LinkSteps.link(w.linker(), duplicate.key(), original.key(), DUPLICATE_OF);

        TrashSteps.delete(w.admin(), original.key());
        ApiResponse answer = LinkSteps.createRequest(w.linker(), duplicate.key(), third.key(), DUPLICATE_OF).send();

        assertThat(LinkSteps.describe(link)).as("the link made").isEqualTo(DUPLICATE_OF + " " + original.key());
        assertThat(LinkSteps.links(w.admin(), duplicate.key())).as("links of the duplicate after the delete")
                .isEmpty();
        assertThat(answer.status()).as("a second original: " + answer.snippet()).isEqualTo(400);
        assertThat(answer.errorCode()).as("code of the second original").isEqualTo("LINK_DUPLICATE_CHAIN");
        assertThat(LinkSteps.links(w.admin(), third.key())).as("links of the third issue").isEmpty();
    }
}
