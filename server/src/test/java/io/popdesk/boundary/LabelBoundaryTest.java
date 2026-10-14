package io.popdesk.boundary;

import static io.popdesk.issue.IssueFlow.NO_KEY;
import static io.popdesk.label.LabelSteps.NO_LABEL;
import static io.popdesk.label.LabelSteps.id;
import static io.popdesk.label.LabelSteps.labelIds;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.label.LabelSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The customer-company boundary of labels and due dates (TM-55, {@code premortem.md} 1): companies A and B
 * ({@link CompanyWorld}) and an agent assigned to A only. B's labels are not listed, added, edited, or deleted
 * through A, and an issue of B gets no label or due date from the agent of A. A refused request answers like a
 * target nobody has and changes nothing, read again afterwards.
 */
class LabelBoundaryTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static void assertSameAnswer(ApiResponse answer, ApiResponse missing, int status, String code) {
        assertThat(missing.status()).as(missing.request() + " -> " + missing.snippet()).isEqualTo(status);
        assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(status);
        assertThat(answer.errorCode()).as("code of " + answer.request()).isEqualTo(code);
        assertThat(answer.text()).as("body of %s, like %s", answer.request(), missing.request())
                .isEqualTo(missing.text());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // S6A-AC-11: the list

    @Test
    @DisplayName("S6A-AC-11 an agent assigned to A only reads A's labels, and gets for B's the 404 of a company code nobody has, also with no assignment at all")
    void agentOfAGetsNoLabelOfB() {
        CompanyWorld w = CompanyWorld.build(fixtures);
        JsonNode ofA = LabelSteps.create(w.admin(), w.a().code());
        LabelSteps.create(w.admin(), w.b().code());
        Session agentOfNothing = fixtures.agent(w.admin());
        String missingCode = Unique.companyCode();

        List<JsonNode> own = LabelSteps.list(w.agentOfA(), w.a().code());

        assertThat(own.stream().map(LabelSteps::id).toList()).as("A's labels for the agent of A")
                .containsExactly(id(ofA));
        for (Session agent : List.of(w.agentOfA(), agentOfNothing)) {
            assertSameAnswer(LabelSteps.listRequest(agent, w.b().code()).send(),
                    LabelSteps.listRequest(agent, missingCode).send(), 404, "NOT_FOUND");
        }
    }

    // S6A-AC-11: writes on an issue of B

    /** A write on an issue that the agent of A tries on B's issue. */
    enum Write {
        ADD_LABEL,
        REMOVE_LABEL,
        SET_DUE_DATE,
        CLEAR_DUE_DATE
    }

    private ApiRequest request(Write write, Session caller, String key, long labelOfB) {
        return switch (write) {
            case ADD_LABEL -> LabelSteps.addRequest(caller, key, labelOfB);
            case REMOVE_LABEL -> LabelSteps.removeRequest(caller, key, labelOfB);
            case SET_DUE_DATE -> LabelSteps.setDueDateRequest(caller, key, today().plusDays(20).toString());
            case CLEAR_DUE_DATE -> LabelSteps.clearDueDateRequest(caller, key);
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("S6A-AC-11, S6A-AC-92 an agent assigned to A only, and an agent assigned to nothing, get the 404 of an issue key nobody has for adding a label of B to B's issue, removing one, and setting or clearing its due date, and B's issue stays as it was")
    void agentOfACannotLabelAnIssueOfB(Write write) {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String key = w.b().publicIssue().key();
        JsonNode onIssue = LabelSteps.create(w.admin(), w.b().code());
        JsonNode notOnIssue = LabelSteps.create(w.admin(), w.b().code());
        LabelSteps.add(w.admin(), key, id(onIssue));
        LabelSteps.setDueDate(w.admin(), key, today().plusDays(10));
        long label = write == Write.ADD_LABEL ? id(notOnIssue) : id(onIssue);
        JsonNode before = flow.detail(w.admin(), key);
        List<JsonNode> historyBefore = flow.history(w.admin(), key);
        flow.tick();

        for (Session caller : List.of(w.agentOfA(), fixtures.agent(w.admin()))) {
            assertSameAnswer(request(write, caller, key, label).send(), request(write, caller, NO_KEY, label).send(),
                    404, "NOT_FOUND");
        }

        assertThat(flow.detail(w.admin(), key)).as("B's issue after the requests").isEqualTo(before);
        assertThat(flow.history(w.admin(), key)).as("history of B's issue after the requests")
                .isEqualTo(historyBefore);
    }

    // S6A-AC-22

    @Test
    @DisplayName("S6A-AC-22 adding a label of B to an issue of A is 400 INVALID_INPUT with the same status and body as a label ID nobody has, for the admin and the agent of A, and A's issue does not get it")
    void labelOfBIsNotAddedToAnIssueOfA() {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String key = w.a().publicIssue().key();
        JsonNode ofA = LabelSteps.create(w.admin(), w.a().code());
        String nameOfB = LabelSteps.name("Secret of B");
        JsonNode ofB = LabelSteps.create(w.admin(), w.b().code(), nameOfB, LabelSteps.color());
        List<JsonNode> historyBefore = flow.history(w.admin(), key);

        for (Session caller : List.of(w.admin(), w.agentOfA())) {
            ApiResponse answer = LabelSteps.addRequest(caller, key, id(ofB)).send();
            ApiResponse missing = LabelSteps.addRequest(caller, key, NO_LABEL).send();

            assertSameAnswer(answer, missing, 400, "INVALID_INPUT");
            assertThat(answer.text()).as("answer to " + answer.request()).doesNotContain(nameOfB);
        }
        JsonNode read = flow.detail(w.admin(), key);
        assertThat(labelIds(read)).as("labels of A's issue read again").isEmpty();
        assertThat(flow.history(w.admin(), key)).as("history of A's issue").isEqualTo(historyBefore);
        assertThat(labelIds(LabelSteps.add(w.agentOfA(), key, id(ofA)))).as("A's issue with A's label")
                .containsExactly(id(ofA));
        assertThat(LabelSteps.find(w.admin(), w.b().code(), id(ofB)).path("issueCount").asLong(-1))
                .as("issueCount of B's label").isZero();
    }

    // S6A-AC-12

    @Test
    @DisplayName("S6A-AC-12 editing and deleting a label of B under A's path are the 404 of a label ID nobody has, and B's label keeps its name and color")
    void labelOfAnotherCompanyInThePathIsNotFound() {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String nameOfB = LabelSteps.name("Kept");
        JsonNode ofB = LabelSteps.create(w.admin(), w.b().code(), nameOfB, "#ABCDEF");
        LabelSteps.create(w.admin(), w.a().code());
        Map<String, Object> change = Map.of("name", LabelSteps.name("Renamed"), "color", "#000000");
        String a = w.a().code();

        assertSameAnswer(LabelSteps.updateRequest(w.admin(), a, id(ofB), change).send(),
                LabelSteps.updateRequest(w.admin(), a, NO_LABEL, change).send(), 404, "NOT_FOUND");
        assertSameAnswer(LabelSteps.deleteRequest(w.admin(), a, id(ofB)).send(),
                LabelSteps.deleteRequest(w.admin(), a, NO_LABEL).send(), 404, "NOT_FOUND");

        JsonNode kept = LabelSteps.find(w.admin(), w.b().code(), id(ofB));
        assertThat(kept).as("B's label after the requests").isNotNull();
        assertThat(text(kept, "name")).as("name of B's label").isEqualTo(nameOfB);
        assertThat(text(kept, "color")).as("color of B's label").isEqualTo("#ABCDEF");
    }

    // S6A-AC-41

    @Test
    @DisplayName("S6A-AC-41 a customer calling the console's label and due date paths gets what a console path already gives a customer, on the portal host and with the session carried to the console host, and nothing changes")
    void customerGetsTheConsoleAnswer() {
        CompanyWorld w = CompanyWorld.build(fixtures);
        String code = w.a().code();
        String key = w.a().publicIssue().key();
        JsonNode label = LabelSteps.create(w.admin(), code);
        LabelSteps.add(w.admin(), key, id(label));
        JsonNode before = flow.detail(w.admin(), key);
        Session customer = w.a().reporter();
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);
        String date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(5).toString();

        for (ApiClient client : List.of(customer.client(), carried)) {
            ApiResponse existing = client.get("/api/console/issues/" + key).send();
            List<ApiResponse> answers = List.of(
                    client.get(LabelSteps.labelsPath(code)).send(),
                    client.post(LabelSteps.labelsPath(code)).json(Map.of("name", "By customer")).send(),
                    client.patch(LabelSteps.labelPath(code, id(label))).json(Map.of("name", "By customer")).send(),
                    client.delete(LabelSteps.labelPath(code, id(label))).send(),
                    client.put(LabelSteps.issueLabelPath(key, id(label))).send(),
                    client.delete(LabelSteps.issueLabelPath(key, id(label))).send(),
                    client.put(LabelSteps.dueDatePath(key)).json(Map.of("dueDate", date)).send(),
                    client.delete(LabelSteps.dueDatePath(key)).send());

            assertThat(existing.status()).as(existing.request() + " -> " + existing.snippet()).isIn(401, 403, 404);
            for (ApiResponse answer : answers) {
                assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet())
                        .isEqualTo(existing.status());
                assertThat(answer.errorCode()).as("code of " + answer.request()).isEqualTo(existing.errorCode());
            }
        }
        assertThat(flow.detail(w.admin(), key)).as("A's issue after the customer's requests").isEqualTo(before);
        assertThat(LabelSteps.list(w.admin(), code).stream().map(LabelSteps::id).toList())
                .as("A's labels after the customer's requests").containsExactly(id(label));
    }
}
