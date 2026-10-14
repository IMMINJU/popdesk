package io.popdesk.label;

import static io.popdesk.label.LabelSteps.assertError;
import static io.popdesk.label.LabelSteps.id;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Customers see no label and no due date (LBL-4, DUE-1, RULE-04, ADR D-41): not in the portal's answers, not
 * in their inbox, not in the mail they get, and the portal list takes no {@code label} or {@code sort}. With
 * SMTP (setup "connected"), so the customer's mail arrives and can be read.
 */
class HiddenFromCustomerTest extends ConnectedAppTest {

    /** Field names of the console that must not show on the portal. */
    private static final List<String> CONSOLE_FIELDS = List.of("labels", "dueDate", "overdue");

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    // S6A-AC-40

    @Test
    @DisplayName("S6A-AC-40 for an issue with a label and a due date, the portal's issue, list, comments, and inbox, and the mails to the customer, hold no label name, color, or due date, and no field labels, dueDate, or overdue")
    void customerSeesNoLabelOrDueDate() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        String name = LabelSteps.name("Angry customer");
        String color = LabelSteps.color();
        JsonNode label = LabelSteps.create(admin, company.code(), name, color);
        LabelSteps.add(admin, issue.key(), id(label));
        LocalDate due = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(37);
        LabelSteps.setDueDate(admin, issue.key(), due);
        fixtures.comment(admin, issue.key(), Unique.text("Answer"));
        flow.moveTo(admin, issue.key(), "RESOLVED");
        TalkFlow.awaitALaterMail(fixtures, admin);
        List<ReceivedMail> mails = mail().messagesTo(customer.email());
        JsonNode console = flow.detail(admin, issue.key());

        String portal = "/api/portal/issues/" + issue.key();
        List<ApiResponse> answers = List.of(
                customer.client().get(portal).expect(200),
                customer.client().get("/api/portal/issues").query("size", 100).expect(200),
                customer.client().get(portal + "/comments").query("size", 100).expect(200),
                customer.client().get("/api/portal/notifications").query("size", 100).expect(200));
        List<JsonNode> inbox = TalkFlow.inboxOf(customer, issue.key());

        assertThat(LabelSteps.labelNames(console)).as("labels of the issue on the console").containsExactly(name);
        assertThat(console.path("dueDate").asString(null)).as("dueDate on the console").isEqualTo(due.toString());
        assertThat(answers.get(1).text()).as("the portal list holds the issue").contains(issue.key());
        assertThat(inbox).as("the customer's inbox items about the issue").isNotEmpty();
        assertThat(mails).as("mails to the customer, for the answer and the resolution").hasSizeGreaterThanOrEqualTo(2);
        List<String> places = new ArrayList<>();
        for (ApiResponse answer : answers) {
            places.add(answer.request() + "\n" + answer.text());
        }
        for (ReceivedMail m : mails) {
            places.add("mail " + m.subject() + "\n" + m.subject() + "\n" + m.text());
        }
        for (String place : places) {
            String lower = place.toLowerCase(Locale.ROOT);
            assertThat(lower).as("label name in %s", place).doesNotContain(name.toLowerCase(Locale.ROOT));
            assertThat(lower).as("label color in %s", place).doesNotContain(color.toLowerCase(Locale.ROOT));
            assertThat(place).as("due date in %s", place).doesNotContain(due.toString());
            for (String field : CONSOLE_FIELDS) {
                assertThat(place).as("field %s in %s", field, place).doesNotContain(field);
            }
        }
    }

    // S6A-AC-42

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"label", "sort"})
    @DisplayName("S6A-AC-42 the portal list answers label and sort with 400 INVALID_INPUT, field code UNKNOWN_PARAMETER")
    void portalListTakesNoLabelOrSort(String parameter) {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        JsonNode label = LabelSteps.create(admin, company.code());
        Object value = parameter.equals("label") ? id(label) : "dueDate";

        ApiResponse answer = customer.client().get("/api/portal/issues").query(parameter, value).send();

        assertError(answer, 400, "INVALID_INPUT");
        List<String> codes = new ArrayList<>();
        for (JsonNode field : answer.json().path("fields")) {
            if (parameter.equals(field.path("field").asString())) {
                codes.add(field.path("code").asString());
            }
        }
        assertThat(codes).as(answer.request()).containsExactly("UNKNOWN_PARAMETER");
    }
}
