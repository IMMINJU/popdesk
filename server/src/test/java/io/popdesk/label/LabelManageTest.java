package io.popdesk.label;

import static io.popdesk.label.LabelSteps.DEFAULT_COLOR;
import static io.popdesk.label.LabelSteps.assertError;
import static io.popdesk.label.LabelSteps.body;
import static io.popdesk.label.LabelSteps.color;
import static io.popdesk.label.LabelSteps.fieldNames;
import static io.popdesk.label.LabelSteps.id;
import static io.popdesk.label.LabelSteps.labels;
import static io.popdesk.label.LabelSteps.list;
import static io.popdesk.label.LabelSteps.name;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.label.LabelSteps.Body;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Managing the labels of a customer company (LBL-1, LBL-3): creating, editing, deleting, and the list. Each
 * test makes its own company, so it counts only labels it made.
 */
class LabelManageTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    /** id, name, color of a label as one list, for comparing. */
    private static List<Object> shape(JsonNode label) {
        return List.of(id(label), String.valueOf(text(label, "name")), String.valueOf(text(label, "color")));
    }

    private static Map<String, Object> nullable(String field, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put(field, value);
        return body;
    }

    // S6A-AC-01

    static Stream<Arguments> colors() {
        return Stream.of(
                Arguments.of("given", "#1F6FEB", "#1F6FEB"),
                Arguments.of("left out", Body.MISSING, DEFAULT_COLOR),
                Arguments.of("null", null, DEFAULT_COLOR));
    }

    @ParameterizedTest(name = "color {0}")
    @MethodSource("colors")
    @DisplayName("S6A-AC-01 an admin creates a label and gets its ID, name, and color; without a color it is #808080")
    void adminCreatesALabel(String what, Object sent, String expected) {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        String name = name("Created");

        ApiResponse created = LabelSteps.createRequest(admin, company.code(), body(name, sent)).send();

        created.expectStatus(201);
        JsonNode label = created.json();
        assertThat(label.path("id").isIntegralNumber()).as("integer id in %s", label).isTrue();
        assertThat(text(label, "name")).as("name").isEqualTo(name);
        assertThat(text(label, "color")).as("color").isEqualTo(expected);
        assertThat(list(admin, company.code()).stream().map(LabelManageTest::shape).toList())
                .as("the company's labels after creating").containsExactly(shape(label));
    }

    // S6A-AC-02

    static Stream<Arguments> badNames() {
        return Stream.of(
                Arguments.of("empty", ""),
                Arguments.of("spaces only", "   "),
                Arguments.of("31 characters", "a".repeat(31)),
                Arguments.of("31 characters after trimming", " " + "b".repeat(31) + " "),
                Arguments.of("null", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badNames")
    @DisplayName("S6A-AC-02 a name that is not 1 to 30 characters once trimmed and NFC-normalized is 400 INVALID_INPUT with field name, on create and on edit, and nothing changes")
    void nameOutOfBounds(String what, String name) {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        JsonNode existing = LabelSteps.create(admin, company.code());

        ApiResponse create = LabelSteps.createRequest(admin, company.code(), body(name, color())).send();
        ApiResponse edit = LabelSteps.updateRequest(admin, company.code(), id(existing), nullable("name", name)).send();

        for (ApiResponse refused : List.of(create, edit)) {
            assertError(refused, 400, "INVALID_INPUT");
            assertThat(fieldNames(refused)).as("fields of " + refused.request()).contains("name");
        }
        assertThat(list(admin, company.code()).stream().map(LabelManageTest::shape).toList())
                .as("the company's labels").containsExactly(shape(existing));
    }

    @Test
    @DisplayName("S6A-AC-02 a name is stored trimmed and NFC-normalized, and 30 characters after that are taken on create and on edit")
    void nameIsTrimmedAndNormalized() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        String composed = "é".repeat(30);
        String decomposed = "é".repeat(30);
        String thirty = "c".repeat(30);

        JsonNode trimmed = LabelSteps.create(admin, company.code(), "  Trim me  ", color());
        JsonNode normalized = LabelSteps.create(admin, company.code(), decomposed, color());
        JsonNode edited = LabelSteps.update(admin, company.code(), id(trimmed), Map.of("name", " " + thirty + " "));

        assertThat(text(trimmed, "name")).as("name sent with spaces around it").isEqualTo("Trim me");
        assertThat(text(normalized, "name")).as("name sent as 60 code points, 30 characters in NFC")
                .isEqualTo(composed);
        assertThat(text(edited, "name")).as("name edited to 30 characters with spaces around").isEqualTo(thirty);
    }

    // S6A-AC-03

    @Test
    @DisplayName("S6A-AC-03 a name that differs only in case from one of the same company is 409 LABEL_NAME_TAKEN on create and on edit; another company may have it, and a label may change the case of its own name")
    void namesAreUniqueIgnoringCase() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        String name = name("Billing");
        JsonNode first = LabelSteps.create(admin, company.code(), name, color());
        JsonNode second = LabelSteps.create(admin, company.code());

        ApiResponse sameCase = LabelSteps.createRequest(admin, company.code(), body(name, color())).send();
        ApiResponse upper = LabelSteps.createRequest(admin, company.code(), body(name.toUpperCase(), color())).send();
        ApiResponse editToIt = LabelSteps.updateRequest(admin, company.code(), id(second),
                Map.of("name", name.toLowerCase())).send();
        JsonNode inOther = LabelSteps.create(admin, other.code(), name, color());
        JsonNode recased = LabelSteps.update(admin, company.code(), id(first), Map.of("name", name.toUpperCase()));

        for (ApiResponse refused : List.of(sameCase, upper, editToIt)) {
            assertError(refused, 409, "LABEL_NAME_TAKEN");
        }
        assertThat(text(inOther, "name")).as("the same name in another company").isEqualTo(name);
        assertThat(text(recased, "name")).as("own name in other case").isEqualTo(name.toUpperCase());
        assertThat(list(admin, company.code()).stream().map(l -> text(l, "name")).toList())
                .as("names of the company's labels")
                .containsExactlyInAnyOrder(name.toUpperCase(), text(second, "name"));
    }

    // S6A-AC-04

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"red", "#12345", "#1234567", "123456", "#GGGGGG", "#12 456", ""})
    @DisplayName("S6A-AC-04 a color that is not # and six hex digits is 400 INVALID_INPUT with field color, on create and on edit")
    void badColor(String color) {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        JsonNode existing = LabelSteps.create(admin, company.code());

        ApiResponse create = LabelSteps.createRequest(admin, company.code(), body(name("Colored"), color)).send();
        ApiResponse edit = LabelSteps.updateRequest(admin, company.code(), id(existing), Map.of("color", color))
                .send();

        for (ApiResponse refused : List.of(create, edit)) {
            assertError(refused, 400, "INVALID_INPUT");
            assertThat(fieldNames(refused)).as("fields of " + refused.request()).contains("color");
        }
        assertThat(list(admin, company.code()).stream().map(LabelManageTest::shape).toList())
                .as("the company's labels").containsExactly(shape(existing));
    }

    @Test
    @DisplayName("S6A-AC-04 a color sent in lower case is stored and given in upper case, on create and on edit")
    void colorInUpperCase() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);

        JsonNode created = LabelSteps.create(admin, company.code(), name("Lower"), "#a1b2c3");
        JsonNode edited = LabelSteps.update(admin, company.code(), id(created), Map.of("color", "#d4e5f6"));

        assertThat(text(created, "color")).as("color of the created label").isEqualTo("#A1B2C3");
        assertThat(text(edited, "color")).as("color of the edited label").isEqualTo("#D4E5F6");
        assertThat(text(LabelSteps.find(admin, company.code(), id(created)), "color")).as("color in the list")
                .isEqualTo("#D4E5F6");
    }

    // S6A-AC-05, S6A-AC-62

    @Test
    @DisplayName("S6A-AC-05, S6A-AC-62 an admin edits a label's name and color: the issue it is on shows the new ones and keeps its version, a field left out stays, and the history keeps the old name and gets no entry")
    void editingShowsOnTheIssueAndNotInTheHistory() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        String oldName = name("Old");
        JsonNode label = LabelSteps.create(admin, company.code(), oldName, "#111111");
        LabelSteps.add(admin, issue.key(), id(label));
        List<JsonNode> historyBefore = flow.history(admin, issue.key());
        long version = flow.version(admin, issue.key());
        String newName = name("New");

        LabelSteps.update(admin, company.code(), id(label), Map.of("name", newName, "color", "#222222"));
        JsonNode recolored = LabelSteps.update(admin, company.code(), id(label), Map.of("color", "#333333"));

        assertThat(text(recolored, "name")).as("name after an edit of the color alone").isEqualTo(newName);
        List<JsonNode> onIssue = labels(flow.detail(admin, issue.key()));
        assertThat(onIssue.stream().map(LabelManageTest::shape).toList()).as("labels of the issue")
                .containsExactly(List.of(id(label), newName, "#333333"));
        assertThat(flow.version(admin, issue.key())).as("version of the issue after the edits").isEqualTo(version);
        List<JsonNode> history = flow.history(admin, issue.key());
        assertThat(history).as("history after the edits").isEqualTo(historyBefore);
        assertThat(history.stream().filter(e -> "LABEL".equals(text(e, "field"))).map(e -> text(e, "newValue")))
                .as("new values of the LABEL entries").containsExactly(oldName);
    }

    // S6A-AC-06, S6A-AC-62

    @Test
    @DisplayName("S6A-AC-06, S6A-AC-62 an admin deletes a label: it leaves the list and every issue it was on, those issues keep their version, and no history entry is added")
    void deletingRemovesItFromIssuesWithoutHistory() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue first = fixtures.fileIssue(customer, company.code());
        Issue second = fixtures.fileIssue(customer, company.code());
        JsonNode doomed = LabelSteps.create(admin, company.code());
        JsonNode kept = LabelSteps.create(admin, company.code());
        for (Issue issue : List.of(first, second)) {
            LabelSteps.add(admin, issue.key(), id(doomed));
        }
        LabelSteps.add(admin, first.key(), id(kept));
        List<JsonNode> firstHistory = flow.history(admin, first.key());
        List<JsonNode> secondHistory = flow.history(admin, second.key());
        long firstVersion = flow.version(admin, first.key());
        long secondVersion = flow.version(admin, second.key());

        LabelSteps.delete(admin, company.code(), id(doomed));

        assertThat(list(admin, company.code()).stream().map(LabelSteps::id).toList()).as("labels of the company")
                .containsExactly(id(kept));
        assertThat(LabelSteps.labelIds(flow.detail(admin, first.key()))).as("labels of the first issue")
                .containsExactly(id(kept));
        assertThat(LabelSteps.labelIds(flow.detail(admin, second.key()))).as("labels of the second issue").isEmpty();
        assertThat(flow.history(admin, first.key())).as("history of the first issue").isEqualTo(firstHistory);
        assertThat(flow.history(admin, second.key())).as("history of the second issue").isEqualTo(secondHistory);
        assertThat(flow.version(admin, first.key())).as("version of the first issue").isEqualTo(firstVersion);
        assertThat(flow.version(admin, second.key())).as("version of the second issue").isEqualTo(secondVersion);
    }

    // S6A-AC-07

    @Test
    @DisplayName("S6A-AC-07 an agent assigned to the company gets 403 FORBIDDEN for creating, editing, and deleting a label, and the labels stay as they were")
    void agentsCannotManageLabels() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        JsonNode label = LabelSteps.create(admin, company.code());
        List<List<Object>> before = list(admin, company.code()).stream().map(LabelManageTest::shape).toList();

        List<ApiResponse> answers = List.of(
                LabelSteps.createRequest(agent, company.code(), body(name("By agent"), color())).send(),
                LabelSteps.updateRequest(agent, company.code(), id(label), Map.of("name", name("By agent"))).send(),
                LabelSteps.deleteRequest(agent, company.code(), id(label)).send());

        for (ApiResponse answer : answers) {
            assertError(answer, 403, "FORBIDDEN");
        }
        assertThat(list(agent, company.code()).stream().map(LabelManageTest::shape).toList())
                .as("the labels as the agent reads them").isEqualTo(before);
    }

    // S6A-AC-08

    @Test
    @DisplayName("S6A-AC-08 in an ARCHIVED company creating a label is 400 COMPANY_NOT_ACTIVE and adds none; editing and deleting work")
    void archivedCompanyTakesNoNewLabel() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        JsonNode edited = LabelSteps.create(admin, company.code());
        JsonNode deleted = LabelSteps.create(admin, company.code());
        flow.companyStatus(admin, company.code(), "ARCHIVED");
        String newName = name("Archived edit");

        ApiResponse create = LabelSteps.createRequest(admin, company.code(), body(name("Too late"), color())).send();
        JsonNode afterEdit = LabelSteps.update(admin, company.code(), id(edited), Map.of("name", newName));
        LabelSteps.delete(admin, company.code(), id(deleted));

        assertError(create, 400, "COMPANY_NOT_ACTIVE");
        assertThat(text(afterEdit, "name")).as("name after the edit").isEqualTo(newName);
        assertThat(list(admin, company.code()).stream().map(l -> text(l, "name")).toList())
                .as("names of the archived company's labels").containsExactly(newName);
    }

    // S6A-AC-09

    @Test
    @DisplayName("S6A-AC-09 with 200 labels in a company, creating one more is 400 LABEL_LIMIT_EXCEEDED and the company keeps 200")
    void twoHundredLabelsAtMost() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        for (int i = 0; i < 200; i++) {
            LabelSteps.create(admin, company.code(), "L" + i, color());
        }

        ApiResponse oneMore = LabelSteps.createRequest(admin, company.code(), body("L200", color())).send();

        assertError(oneMore, 400, "LABEL_LIMIT_EXCEEDED");
        List<String> names = list(admin, company.code()).stream().map(l -> text(l, "name")).toList();
        assertThat(names).as("labels of the company").hasSize(200).doesNotContain("L200");
    }

    // S6A-AC-10

    @Test
    @DisplayName("S6A-AC-10 staff read the company's labels by name ignoring case, each with ID, name, color, and the number of issues it is on")
    void listIsByNameWithIssueCounts() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session customer = fixtures.customer(admin, company.code());
        Issue first = fixtures.fileIssue(customer, company.code());
        Issue second = fixtures.fileIssue(customer, company.code());
        JsonNode beta = LabelSteps.create(admin, company.code(), "beta", "#000001");
        JsonNode alpha = LabelSteps.create(admin, company.code(), "Alpha", "#000002");
        JsonNode gamma = LabelSteps.create(admin, company.code(), "gamma", "#000003");
        JsonNode delta = LabelSteps.create(admin, company.code(), "Delta", "#000004");
        LabelSteps.add(agent, first.key(), id(beta));
        LabelSteps.add(agent, second.key(), id(beta));
        LabelSteps.add(agent, first.key(), id(alpha));

        List<JsonNode> byAdmin = list(admin, company.code());
        List<JsonNode> byAgent = list(agent, company.code());

        List<List<Object>> expected = new ArrayList<>();
        expected.add(List.of(id(alpha), "Alpha", "#000002", 1L));
        expected.add(List.of(id(beta), "beta", "#000001", 2L));
        expected.add(List.of(id(delta), "Delta", "#000004", 0L));
        expected.add(List.of(id(gamma), "gamma", "#000003", 0L));
        for (List<JsonNode> labels : List.of(byAdmin, byAgent)) {
            assertThat(labels.stream().map(l -> List.<Object>of(id(l), String.valueOf(text(l, "name")),
                    String.valueOf(text(l, "color")), l.path("issueCount").asLong(-1))).toList())
                    .as("id, name, color, issueCount of each label, in order").isEqualTo(expected);
        }
    }
}
