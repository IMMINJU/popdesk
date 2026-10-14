package io.popdesk.label;

import static io.popdesk.issue.IssueFlow.assertEmptyPage;
import static io.popdesk.issue.IssueFlow.list;
import static io.popdesk.issue.IssueFlow.listRequest;
import static io.popdesk.issue.IssueFlow.total;
import static io.popdesk.label.LabelSteps.NO_LABEL;
import static io.popdesk.label.LabelSteps.assertError;
import static io.popdesk.label.LabelSteps.fieldNames;
import static io.popdesk.label.LabelSteps.id;
import static io.popdesk.label.LabelSteps.items;
import static io.popdesk.label.LabelSteps.labels;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Labels in the console issue list and the {@code label} filter (ISSUE-5, COM-4). The context is shared, so a
 * test lists as an agent assigned to its own company alone, or filters by a label it made, which no issue of
 * another test has.
 */
class LabelListTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static List<String> keys(ApiResponse page) {
        return items(page).stream().map(n -> text(n, "key")).toList();
    }

    private static JsonNode item(ApiResponse page, String key) {
        return items(page).stream().filter(n -> key.equals(text(n, "key"))).findFirst()
                .orElseThrow(() -> new AssertionError("No " + key + " in " + page.request()));
    }

    /** id, name, color of each label, in the order given. */
    private static List<List<Object>> shapes(JsonNode issue) {
        return labels(issue).stream()
                .map(l -> List.<Object>of(id(l), String.valueOf(text(l, "name")), String.valueOf(text(l, "color"))))
                .toList();
    }

    // S6A-AC-50

    @Test
    @DisplayName("S6A-AC-50 a list item and the issue carry its labels with ID, name, and color, by name ignoring case; an issue without labels has an empty array")
    void listAndIssueCarryLabels() {
        World w = flow.world();
        Issue labelled = fixtures.fileIssue(w.reporter(), w.code());
        Issue bare = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode beta = LabelSteps.create(w.admin(), w.code(), "beta", "#0000AA");
        JsonNode alpha = LabelSteps.create(w.admin(), w.code(), "Alpha", "#0000BB");
        JsonNode zulu = LabelSteps.create(w.admin(), w.code(), "Zulu", "#0000CC");
        for (JsonNode label : List.of(beta, alpha, zulu)) {
            LabelSteps.add(w.agent(), labelled.key(), id(label));
        }

        ApiResponse page = list(w.agent(), "companyCode", w.code());
        JsonNode detail = flow.detail(w.agent(), labelled.key());

        List<List<Object>> expected = List.of(
                List.of(id(alpha), "Alpha", "#0000BB"),
                List.of(id(beta), "beta", "#0000AA"),
                List.of(id(zulu), "Zulu", "#0000CC"));
        assertThat(shapes(item(page, labelled.key()))).as("labels of the list item").isEqualTo(expected);
        assertThat(shapes(detail)).as("labels of the issue").isEqualTo(expected);
        assertThat(labels(item(page, bare.key()))).as("labels of the item without labels").isEmpty();
        assertThat(labels(flow.detail(w.agent(), bare.key()))).as("labels of the issue without labels").isEmpty();
    }

    // S6A-AC-51

    @Test
    @DisplayName("S6A-AC-51 the label filter gives the issues with that label; given several times, those with any of them; with another filter, those that match both")
    void labelFilter() {
        World w = flow.world();
        JsonNode one = LabelSteps.create(w.admin(), w.code());
        JsonNode two = LabelSteps.create(w.admin(), w.code());
        Issue withOne = fixtures.fileIssue(w.reporter(), w.code());
        Issue withTwo = fixtures.fileIssue(w.reporter(), w.code());
        Issue withBoth = fixtures.fileIssue(w.reporter(), w.code());
        Issue withNone = fixtures.fileIssue(w.reporter(), w.code());
        LabelSteps.add(w.agent(), withOne.key(), id(one));
        LabelSteps.add(w.agent(), withTwo.key(), id(two));
        LabelSteps.add(w.agent(), withBoth.key(), id(one));
        LabelSteps.add(w.agent(), withBoth.key(), id(two));
        flow.changeStatus(w.agent(), withBoth.key(), "CONFIRMED");
        flow.changeStatus(w.agent(), withNone.key(), "CONFIRMED");

        for (Session staff : List.of(w.agent(), w.admin())) {
            ApiResponse byOne = list(staff, "label", id(one));
            ApiResponse byEither = list(staff, "label", id(one), "label", id(two));
            ApiResponse byOneAndStatus = list(staff, "label", id(one), "status", "CONFIRMED");

            assertThat(keys(byOne)).as("label=one for %s", staff.role())
                    .containsExactlyInAnyOrder(withOne.key(), withBoth.key());
            assertThat(total(byOne)).as("totalElements of label=one").isEqualTo(2);
            assertThat(keys(byEither)).as("label=one&label=two for %s", staff.role())
                    .containsExactlyInAnyOrder(withOne.key(), withTwo.key(), withBoth.key());
            assertThat(total(byEither)).as("totalElements of label=one&label=two").isEqualTo(3);
            assertThat(keys(byOneAndStatus)).as("label=one&status=CONFIRMED for %s", staff.role())
                    .containsExactly(withBoth.key());
            assertThat(total(byOneAndStatus)).as("totalElements of label=one&status=CONFIRMED").isEqualTo(1);
        }
    }

    // S6A-AC-52

    @Test
    @DisplayName("S6A-AC-52 a label of a company the agent is not assigned to, and a label ID nobody has, give an empty list with totalElements 0; an admin gets the issue with that label")
    void labelOutOfScopeGivesAnEmptyList() {
        World w = flow.world();
        JsonNode label = LabelSteps.create(w.admin(), w.code());
        Issue labelled = fixtures.fileIssue(w.reporter(), w.code());
        LabelSteps.add(w.agent(), labelled.key(), id(label));

        ApiResponse agentElsewhere = list(w.agentElsewhere(), "label", id(label));
        ApiResponse agentOfNothing = list(w.unassignedAgent(), "label", id(label));
        ApiResponse agentMissing = list(w.agentElsewhere(), "label", NO_LABEL);
        ApiResponse adminMissing = list(w.admin(), "label", NO_LABEL);
        ApiResponse admin = list(w.admin(), "label", id(label));

        assertEmptyPage(agentElsewhere, "the list of an agent of another company, by a label of this one");
        assertEmptyPage(agentOfNothing, "the list of an agent assigned to nothing, by a label");
        assertEmptyPage(agentMissing, "an agent's list by a label ID nobody has");
        assertEmptyPage(adminMissing, "an admin's list by a label ID nobody has");
        assertThat(agentElsewhere.json()).as("body for a label out of scope vs a label nobody has")
                .isEqualTo(agentMissing.json());
        assertThat(keys(admin)).as("the admin's list by the same label").containsExactly(labelled.key());
        assertThat(total(admin)).as("totalElements of the admin's list").isEqualTo(1);
    }

    @Test
    @DisplayName("S6A-AC-52 a label that is not an ID is 400 INVALID_INPUT with field label, also next to a good one")
    void labelThatIsNotAnId() {
        Session admin = flow.admin();

        ApiResponse alone = listRequest(admin, "label", "bug").send();
        ApiResponse withGood = listRequest(admin, "label", 1, "label", "bug").send();

        for (ApiResponse refused : List.of(alone, withGood)) {
            assertError(refused, 400, "INVALID_INPUT");
            assertThat(fieldNames(refused)).as("fields of " + refused.request()).contains("label");
        }
    }

    // S6A-AC-54

    @Test
    @DisplayName("S6A-AC-54 filtered by label, issues filed at the same time come once each over the pages, and every page counts them all")
    void labelFilterKeepsThePages() {
        World w = flow.world();
        JsonNode label = LabelSteps.create(w.admin(), w.code());
        List<String> labelled = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Issue issue = fixtures.fileIssue(w.reporter(), w.code());
            LabelSteps.add(w.agent(), issue.key(), id(label));
            labelled.add(issue.key());
        }
        fixtures.fileIssue(w.reporter(), w.code());

        List<String> seen = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            ApiResponse response = listRequest(w.agent(), "label", id(label)).query("size", 2).query("page", page)
                    .expect(200);
            assertThat(total(response)).as("totalElements on page %s", page).isEqualTo(5);
            assertThat(response.json().path("totalPages").asInt(-1)).as("totalPages on page %s", page).isEqualTo(3);
            seen.addAll(keys(response));
        }

        assertThat(seen).as("keys over the three pages").doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(labelled);
    }
}
