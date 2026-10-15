package io.popdesk.link;

import static io.popdesk.link.LinkSteps.DUPLICATED_BY;
import static io.popdesk.link.LinkSteps.DUPLICATE_OF;
import static io.popdesk.link.LinkSteps.RELATED;
import static io.popdesk.link.LinkSteps.historyLink;
import static io.popdesk.link.LinkSteps.linkEntries;
import static io.popdesk.link.LinkSteps.titleOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@code LINK} entries of the issue history (LINK-7, HIST-1 and 2): one on each issue for each link made or
 * removed, the other issue as {@code oldLink} and {@code newLink}, left out for a reader who cannot see the other
 * issue, and no change to {@code version} or {@code updatedAt}. The stored value is checked only for what it must
 * not hold (the other issue's key and title), not for its form ({@code acceptance.md} 4절).
 */
class LinkHistoryTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static void assertActor(JsonNode entry, Session who) {
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(who.accountId());
    }

    // S6C-AC-20, S6C-AC-21

    @Test
    @DisplayName("S6C-AC-20, S6C-AC-21 linking and removing write one LINK entry on each issue by the one who asked, with the other issue's key, title, and the type seen from that issue in newLink or oldLink and no text values; the database holds neither issue's key or title anywhere new")
    void linkAndRemoveAreEntriesOnBothIssues() {
        World w = LinkSteps.world(fixtures, flow);
        Issue duplicate = w.issueOfA(fixtures);
        Issue original = w.issueOfB(fixtures);
        Session remover = fixtures.agent(w.admin(), w.a().code(), w.b().code());
        Map<String, String> needles = Map.of("duplicate key", duplicate.key(), "duplicate title", titleOf(duplicate),
                "original key", original.key(), "original title", titleOf(original));
        Map<String, Map<String, Long>> before = new TreeMap<>();
        needles.forEach((name, needle) -> before.put(name, rowsHolding(needle)));

        Instant linkedAt = flow.tick();
        LinkSteps.link(w.linker(), duplicate.key(), original.key(), DUPLICATE_OF);
        Instant removedAt = flow.tick();
        LinkSteps.unlink(remover, original.key(), duplicate.key());

        List<JsonNode> ofDuplicate = linkEntries(w.admin(), duplicate.key());
        List<JsonNode> ofOriginal = linkEntries(w.admin(), original.key());
        assertThat(ofDuplicate).as("LINK entries of the duplicate").hasSize(2);
        assertThat(ofOriginal).as("LINK entries of the original").hasSize(2);
        String toOriginal = DUPLICATE_OF + " " + original.key() + " " + titleOf(original);
        String toDuplicate = DUPLICATED_BY + " " + duplicate.key() + " " + titleOf(duplicate);
        assertThat(historyLink(ofDuplicate.get(0), "newLink")).as("link made, on the duplicate").isEqualTo(toOriginal);
        assertThat(historyLink(ofDuplicate.get(0), "oldLink")).as("oldLink of the link made").isNull();
        assertThat(historyLink(ofDuplicate.get(1), "oldLink")).as("link removed, on the duplicate")
                .isEqualTo(toOriginal);
        assertThat(historyLink(ofDuplicate.get(1), "newLink")).as("newLink of the link removed").isNull();
        assertThat(historyLink(ofOriginal.get(0), "newLink")).as("link made, on the original").isEqualTo(toDuplicate);
        assertThat(historyLink(ofOriginal.get(1), "oldLink")).as("link removed, on the original")
                .isEqualTo(toDuplicate);
        for (List<JsonNode> entries : List.of(ofDuplicate, ofOriginal)) {
            assertActor(entries.get(0), w.linker());
            assertActor(entries.get(1), remover);
            assertThat(IssueFlow.time(entries.get(0), "createdAt")).as("time of the link made").isEqualTo(linkedAt);
            assertThat(IssueFlow.time(entries.get(1), "createdAt")).as("time of the link removed")
                    .isEqualTo(removedAt);
            for (JsonNode entry : entries) {
                assertThat(entry.path("oldValue").isNull() && entry.path("newValue").isNull())
                        .as("oldValue and newValue are null in %s", entry).isTrue();
                assertThat(entry.path("oldPerson").isNull() && entry.path("newPerson").isNull())
                        .as("no people in %s", entry).isTrue();
            }
        }
        for (Map.Entry<String, Map<String, Long>> was : before.entrySet()) {
            Map<String, Long> now = rowsHolding(needles.get(was.getKey()));
            for (Map.Entry<String, Long> column : now.entrySet()) {
                assertThat(column.getValue()).as("rows of %s holding the %s, after linking and removing",
                        column.getKey(), was.getKey()).isLessThanOrEqualTo(was.getValue().getOrDefault(column.getKey(),
                        0L));
            }
        }
    }

    /**
     * For each {@code table.column} of the app's schema that holds {@code needle} ({@link TalkFlow#tablesContaining}),
     * the number of its rows that hold it.
     */
    private Map<String, Long> rowsHolding(String needle) {
        Map<String, Long> out = new TreeMap<>();
        for (String place : TalkFlow.tablesContaining(db, needle)) {
            int dot = place.indexOf('.');
            String table = place.substring(0, dot);
            String column = place.substring(dot + 1);
            String type = db.columns(table).stream().filter(c -> c.name().equals(column)).findFirst()
                    .orElseThrow().dataType();
            String where = type.equals("bytea") ? "position(convert_to(?, 'UTF8') in \"" + column + "\") > 0"
                    : "position(lower(?) in lower(\"" + column + "\"::text)) > 0";
            out.put(place, db.count("select count(*) from \"" + table + "\" where " + where, needle));
        }
        return out;
    }

    // S6C-AC-22

    @Test
    @DisplayName("S6C-AC-22 the agent of A only reads the history of A's issue without the LINK entries to B's issue, also in totalElements, while CREATED, other changes, and the LINK entry to another issue of A stay; once assigned to B the entries are back")
    void entriesAboutAnUnseenIssueAreLeftOut() {
        World w = LinkSteps.world(fixtures, flow);
        Issue ofA = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        Issue otherOfA = w.issueOfA(fixtures);
        flow.changeStatus(w.admin(), ofA.key(), "CONFIRMED");
        LinkSteps.link(w.linker(), ofA.key(), ofB.key(), RELATED);
        LinkSteps.link(w.linker(), ofA.key(), otherOfA.key(), RELATED);
        LinkSteps.unlink(w.linker(), ofA.key(), ofB.key());
        LinkSteps.link(w.linker(), ofA.key(), ofB.key(), DUPLICATE_OF);

        ApiResponse all = LinkSteps.historyPage(w.admin(), ofA.key(), 1, 100);
        ApiResponse seenByA = LinkSteps.historyPage(w.agentOfA(), ofA.key(), 1, 100);
        ApiResponse firstPageByA = LinkSteps.historyPage(w.agentOfA(), ofA.key(), 1, 2);

        assertThat(linkEntries(w.admin(), ofA.key())).as("LINK entries the admin sees").hasSize(4);
        assertThat(all.jsonLong("totalElements")).as("entries the admin counts").isEqualTo(6);
        assertThat(seenByA.jsonLong("totalElements")).as("entries the agent of A counts").isEqualTo(3);
        assertThat(firstPageByA.jsonLong("totalPages")).as("pages of 2 for the agent of A").isEqualTo(2);
        List<JsonNode> entries = LinkSteps.history(w.agentOfA(), ofA.key());
        assertThat(entries).extracting(e -> e.path("kind").asString(null) + " " + e.path("field").asString(null))
                .as("entries the agent of A sees").containsExactly("CREATED null", "CHANGED STATUS", "CHANGED LINK");
        assertThat(historyLink(entries.get(2), "newLink")).as("the LINK entry to the other issue of A")
                .isEqualTo(RELATED + " " + otherOfA.key() + " " + titleOf(otherOfA));
        assertThat(seenByA.text()).as("history the agent of A reads").doesNotContain(ofB.key())
                .doesNotContain(titleOf(ofB));

        fixtures.assignAgent(w.admin(), w.b().code(), w.agentOfA().accountId());

        assertThat(LinkSteps.historyPage(w.agentOfA(), ofA.key(), 1, 100).jsonLong("totalElements"))
                .as("entries once assigned to B").isEqualTo(6);
        assertThat(linkEntries(w.agentOfA(), ofA.key())).as("LINK entries once assigned to B").hasSize(4);
    }

    // S6C-AC-23

    @Test
    @DisplayName("S6C-AC-23 linking and removing a link change neither issue's version nor updatedAt, on the console and on the portal")
    void versionAndUpdatedAtStay() {
        World w = LinkSteps.world(fixtures, flow);
        Issue ofA = w.issueOfA(fixtures);
        Issue ofB = w.issueOfB(fixtures);
        List<Session> readers = List.of(w.admin(), w.reporterA(), w.admin(), w.reporterB());
        List<String> keys = List.of(ofA.key(), ofA.key(), ofB.key(), ofB.key());
        List<String> before = stamps(readers, keys);

        flow.tick();
        LinkSteps.link(w.linker(), ofA.key(), ofB.key(), DUPLICATE_OF);
        List<String> linked = stamps(readers, keys);
        flow.tick();
        LinkSteps.unlink(w.linker(), ofB.key(), ofA.key());
        List<String> removed = stamps(readers, keys);

        assertThat(before).as("version and updatedAt of both issues on both apps")
                .allSatisfy(s -> assertThat(s).doesNotContain("null"));
        assertThat(linked).as("after linking").isEqualTo(before);
        assertThat(removed).as("after removing").isEqualTo(before);
    }

    /** {@code version} and {@code updatedAt} of each issue as each reader reads it. */
    private List<String> stamps(List<Session> readers, List<String> keys) {
        return IntStream.range(0, keys.size()).mapToObj(i -> {
            JsonNode issue = flow.detail(readers.get(i), keys.get(i));
            return readers.get(i).app() + " " + keys.get(i) + " version " + issue.path("version").asString("null")
                    + " updatedAt " + issue.path("updatedAt").asString("null");
        }).toList();
    }
}
