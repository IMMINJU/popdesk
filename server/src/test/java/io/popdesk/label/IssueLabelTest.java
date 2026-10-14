package io.popdesk.label;

import static io.popdesk.label.LabelSteps.assertError;
import static io.popdesk.label.LabelSteps.fieldNames;
import static io.popdesk.label.LabelSteps.id;
import static io.popdesk.label.LabelSteps.labelIds;
import static io.popdesk.label.LabelSteps.labels;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Adding labels to an issue and removing them (LBL-2, LBL-3, HIST-1): what the answer and the issue hold,
 * the history, the limit, a {@code CLOSED} issue, and adding while the label is deleted. Each test makes its
 * own company, issue, and labels.
 */
class IssueLabelTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** An admin, a company, an agent assigned to it, its customer, and one issue of that customer. */
    private record Scene(Session admin, Company company, Session agent, Session customer, Issue issue) {

        String code() {
            return company.code();
        }

        String key() {
            return issue.key();
        }
    }

    private Scene scene() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session customer = fixtures.customer(admin, company.code());
        return new Scene(admin, company, agent, customer, fixtures.fileIssue(customer, company.code()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private List<JsonNode> labelEntries(Session staff, String key) {
        return flow.history(staff, key).stream().filter(e -> "LABEL".equals(text(e, "field"))).toList();
    }

    // S6A-AC-20

    @Test
    @DisplayName("S6A-AC-20 staff add a label of the issue's company: the answer is the issue with that label's ID, name, and color; adding it again is 200 with the same labels and no second history entry")
    void addingALabel() {
        Scene s = scene();
        JsonNode label = LabelSteps.create(s.admin(), s.code(), LabelSteps.name("Bug"), "#D73A4A");

        JsonNode added = LabelSteps.add(s.agent(), s.key(), id(label));
        JsonNode again = LabelSteps.add(s.agent(), s.key(), id(label));

        assertThat(text(added, "key")).as("key of the answer").isEqualTo(s.key());
        List<JsonNode> onIssue = labels(added);
        assertThat(onIssue).as("labels in the answer").hasSize(1);
        assertThat(id(onIssue.getFirst())).as("id").isEqualTo(id(label));
        assertThat(text(onIssue.getFirst(), "name")).as("name").isEqualTo(text(label, "name"));
        assertThat(text(onIssue.getFirst(), "color")).as("color").isEqualTo("#D73A4A");
        assertThat(labels(again)).as("labels after adding again").isEqualTo(onIssue);
        assertThat(labels(flow.detail(s.agent(), s.key()))).as("labels of the issue read again").isEqualTo(onIssue);
        assertThat(labelEntries(s.agent(), s.key())).as("LABEL entries of the history").hasSize(1);
    }

    // S6A-AC-21

    @Test
    @DisplayName("S6A-AC-21 staff remove a label: the answer is the issue without it; removing it again, a label never on the issue, or a deleted one is 200 with no history entry")
    void removingALabel() {
        Scene s = scene();
        JsonNode removed = LabelSteps.create(s.admin(), s.code());
        JsonNode kept = LabelSteps.create(s.admin(), s.code());
        JsonNode neverOn = LabelSteps.create(s.admin(), s.code());
        JsonNode deleted = LabelSteps.create(s.admin(), s.code());
        LabelSteps.add(s.agent(), s.key(), id(removed));
        LabelSteps.add(s.agent(), s.key(), id(kept));
        LabelSteps.add(s.agent(), s.key(), id(deleted));
        LabelSteps.delete(s.admin(), s.code(), id(deleted));

        JsonNode answer = LabelSteps.remove(s.agent(), s.key(), id(removed));
        int entries = labelEntries(s.agent(), s.key()).size();
        List<JsonNode> quiet = List.of(
                LabelSteps.remove(s.agent(), s.key(), id(removed)),
                LabelSteps.remove(s.agent(), s.key(), id(neverOn)),
                LabelSteps.remove(s.agent(), s.key(), id(deleted)));

        assertThat(labelIds(answer)).as("labels in the answer").containsExactly(id(kept));
        for (JsonNode issue : quiet) {
            assertThat(labelIds(issue)).as("labels in the answer of a removal that changes nothing")
                    .containsExactly(id(kept));
        }
        assertThat(labelIds(flow.detail(s.agent(), s.key()))).as("labels of the issue read again")
                .containsExactly(id(kept));
        assertThat(labelEntries(s.agent(), s.key())).as("LABEL entries after the removals that change nothing")
                .hasSize(entries);
    }

    // S6A-AC-23 (the race with the closing is issue.LabelCloseRaceTest)

    @Test
    @DisplayName("S6A-AC-23 on a CLOSED issue adding and removing a label are 400 ISSUE_CLOSED and the labels stay")
    void closedIssueKeepsItsLabels() {
        Scene s = scene();
        JsonNode on = LabelSteps.create(s.admin(), s.code());
        JsonNode other = LabelSteps.create(s.admin(), s.code());
        LabelSteps.add(s.agent(), s.key(), id(on));
        flow.changeStatus(s.agent(), s.key(), "CLOSED");
        List<JsonNode> history = flow.history(s.agent(), s.key());

        ApiResponse add = LabelSteps.addRequest(s.agent(), s.key(), id(other)).send();
        ApiResponse remove = LabelSteps.removeRequest(s.agent(), s.key(), id(on)).send();

        assertError(add, 400, "ISSUE_CLOSED");
        assertError(remove, 400, "ISSUE_CLOSED");
        assertThat(labelIds(flow.detail(s.agent(), s.key()))).as("labels of the closed issue").containsExactly(id(on));
        assertThat(flow.history(s.agent(), s.key())).as("history of the closed issue").isEqualTo(history);
    }

    // S6A-AC-24

    @Test
    @DisplayName("S6A-AC-24 with 20 labels on an issue, adding another is 400 ISSUE_LABEL_LIMIT_EXCEEDED and the issue keeps its 20")
    void twentyLabelsOnAnIssueAtMost() {
        Scene s = scene();
        List<Long> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long label = id(LabelSteps.create(s.admin(), s.code()));
            LabelSteps.add(s.agent(), s.key(), label);
            twenty.add(label);
        }
        JsonNode oneMore = LabelSteps.create(s.admin(), s.code());

        ApiResponse refused = LabelSteps.addRequest(s.agent(), s.key(), id(oneMore)).send();

        assertError(refused, 400, "ISSUE_LABEL_LIMIT_EXCEEDED");
        assertThat(labelIds(flow.detail(s.agent(), s.key()))).as("labels of the issue")
                .containsExactlyInAnyOrderElementsOf(twenty);
    }

    // S6A-AC-25

    @Test
    @DisplayName("S6A-AC-25 adding a label while it is deleted, 20 times at once: every label is gone from the company and the issue, and each adding is 200 or 400 INVALID_INPUT, never 5xx")
    void addingWhileTheLabelIsDeleted() {
        Scene s = scene();
        List<Long> doomed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            doomed.add(id(LabelSteps.create(s.admin(), s.code())));
        }
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (long label : doomed) {
            calls.add(() -> LabelSteps.addRequest(s.agent(), s.key(), label).send());
            calls.add(() -> LabelSteps.deleteRequest(s.admin(), s.code(), label).send());
        }

        List<ApiResponse> answers = Concurrently.send(calls.size(), calls);

        for (int i = 0; i < answers.size(); i += 2) {
            ApiResponse adding = answers.get(i);
            ApiResponse deleting = answers.get(i + 1);
            assertThat(deleting.status()).as(deleting.request() + " -> " + deleting.snippet()).isEqualTo(200);
            assertThat(adding.status()).as(adding.request() + " -> " + adding.snippet()).isIn(200, 400);
            if (adding.status() == 400) {
                assertThat(adding.errorCode()).as("code of " + adding.request()).isEqualTo("INVALID_INPUT");
                assertThat(fieldNames(adding)).as("fields of " + adding.request()).contains("id");
            }
        }
        assertThat(LabelSteps.list(s.admin(), s.code())).as("labels of the company").isEmpty();
        assertThat(labelIds(flow.detail(s.agent(), s.key()))).as("labels of the issue").isEmpty();
    }

    // S6A-AC-26

    @Test
    @DisplayName("S6A-AC-26 reopening a closed issue keeps its labels and its due date")
    void reopeningKeepsLabelsAndDueDate() {
        Scene s = scene();
        JsonNode first = LabelSteps.create(s.admin(), s.code());
        JsonNode second = LabelSteps.create(s.admin(), s.code());
        LabelSteps.add(s.agent(), s.key(), id(first));
        LabelSteps.add(s.agent(), s.key(), id(second));
        LocalDate due = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(30);
        JsonNode set = LabelSteps.setDueDate(s.agent(), s.key(), due);
        flow.changeStatus(s.agent(), s.key(), "CLOSED");

        JsonNode reopened = flow.reopen(s.agent(), s.key());

        JsonNode read = flow.detail(s.agent(), s.key());
        assertThat(text(read, "status")).as("status after reopening").isNotEqualTo("CLOSED");
        for (JsonNode issue : List.of(reopened, read)) {
            assertThat(labels(issue)).as("labels after reopening").isEqualTo(labels(set));
            assertThat(labelIds(issue)).as("label ids after reopening")
                    .containsExactlyInAnyOrder(id(first), id(second));
            assertThat(text(issue, "dueDate")).as("dueDate after reopening").isEqualTo(due.toString());
        }
    }

    // S6A-AC-60

    @Test
    @DisplayName("S6A-AC-60 adding and removing a label leave LABEL entries: adding has the name of the time as newValue, removing as oldValue, with who and when")
    void historyOfLabels() {
        Scene s = scene();
        String firstName = LabelSteps.name("First");
        JsonNode label = LabelSteps.create(s.admin(), s.code(), firstName, LabelSteps.color());
        Instant addedAt = flow.tick();
        LabelSteps.add(s.agent(), s.key(), id(label));
        String secondName = LabelSteps.name("Second");
        LabelSteps.update(s.admin(), s.code(), id(label), Map.of("name", secondName));
        Instant removedAt = flow.tick();

        LabelSteps.remove(s.admin(), s.key(), id(label));

        List<JsonNode> entries = labelEntries(s.admin(), s.key());
        assertThat(entries).as("LABEL entries").hasSize(2);
        assertEntry(entries.get(0), null, firstName, s.agent(), "AGENT", addedAt);
        assertEntry(entries.get(1), secondName, null, s.admin(), "ADMIN", removedAt);
    }

    private static void assertEntry(JsonNode entry, String oldValue, String newValue, Session who, String role,
            Instant at) {
        assertThat(text(entry, "kind")).as("kind of %s", entry).isEqualTo("CHANGED");
        assertThat(entry.path("oldValue").isNull() ? null : text(entry, "oldValue")).as("oldValue of %s", entry)
                .isEqualTo(oldValue);
        assertThat(entry.path("newValue").isNull() ? null : text(entry, "newValue")).as("newValue of %s", entry)
                .isEqualTo(newValue);
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(who.accountId());
        assertThat(text(entry.path("actor"), "role")).as("actor role of %s", entry).isEqualTo(role);
        assertThat(IssueFlow.time(entry, "createdAt")).as("createdAt of %s", entry).isEqualTo(at);
    }
}
