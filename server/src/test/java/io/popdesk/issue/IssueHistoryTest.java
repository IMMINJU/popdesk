package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.time;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The history of an issue ({@code GET /api/console/issues/{key}/history}): one {@code CREATED} entry
 * when it is filed, one {@code CHANGED} entry per field that really changed, oldest first, for staff
 * only.
 */
class IssueHistoryTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static String historyPath(String key) {
        return "/api/console/issues/" + key + "/history";
    }

    private static boolean isNull(JsonNode entry, String field) {
        return entry.has(field) && entry.path(field).isNull();
    }

    private static long personId(JsonNode entry, String field) {
        return entry.path(field).path("accountId").asLong(-1);
    }

    private static void assertActor(JsonNode entry, Session who, String role) {
        JsonNode actor = entry.path("actor");
        assertThat(actor.path("accountId").asLong(-1)).as("actor of %s", entry).isEqualTo(who.accountId());
        assertThat(text(actor, "name")).as("actor name of %s", entry).isEqualTo(who.name());
        assertThat(text(actor, "role")).as("actor role of %s", entry).isEqualTo(role);
    }

    /** A {@code CHANGED} entry of a text field: the field, both values, no people, who, and when. */
    private static void assertChange(JsonNode entry, String field, String oldValue, String newValue, Session who,
            String role, Instant at) {
        assertThat(text(entry, "kind")).as("kind of %s", entry).isEqualTo("CHANGED");
        assertThat(text(entry, "field")).as("field of %s", entry).isEqualTo(field);
        assertThat(text(entry, "oldValue")).as("oldValue of %s", entry).isEqualTo(oldValue);
        assertThat(text(entry, "newValue")).as("newValue of %s", entry).isEqualTo(newValue);
        assertThat(isNull(entry, "oldPerson") && isNull(entry, "newPerson")).as("no people in %s", entry).isTrue();
        assertActor(entry, who, role);
        assertThat(time(entry, "createdAt")).as("createdAt of %s", entry).isEqualTo(at);
    }

    /** A {@code CHANGED} entry of the assignee: people instead of text values; 0 means nobody. */
    private static void assertAssigneeChange(JsonNode entry, long oldId, long newId, Session who, String role,
            Instant at) {
        assertThat(text(entry, "kind")).as("kind of %s", entry).isEqualTo("CHANGED");
        assertThat(text(entry, "field")).as("field of %s", entry).isEqualTo("ASSIGNEE");
        assertThat(isNull(entry, "oldValue") && isNull(entry, "newValue")).as("no text values in %s", entry)
                .isTrue();
        if (oldId == 0) {
            assertThat(isNull(entry, "oldPerson")).as("oldPerson is null in %s", entry).isTrue();
        } else {
            assertThat(personId(entry, "oldPerson")).as("oldPerson of %s", entry).isEqualTo(oldId);
        }
        if (newId == 0) {
            assertThat(isNull(entry, "newPerson")).as("newPerson is null in %s", entry).isTrue();
        } else {
            assertThat(personId(entry, "newPerson")).as("newPerson of %s", entry).isEqualTo(newId);
        }
        assertActor(entry, who, role);
        assertThat(time(entry, "createdAt")).as("createdAt of %s", entry).isEqualTo(at);
    }

    private static void assertCreated(JsonNode entry, Session who, String role, Instant at) {
        assertThat(text(entry, "kind")).as("kind of %s", entry).isEqualTo("CREATED");
        for (String field : List.of("field", "oldValue", "newValue", "oldPerson", "newPerson")) {
            assertThat(isNull(entry, field)).as("%s is null in %s", field, entry).isTrue();
        }
        assertActor(entry, who, role);
        assertThat(time(entry, "createdAt")).as("createdAt of %s", entry).isEqualTo(at);
    }

    // S3-AC-61

    @Test
    @DisplayName("S3-AC-61 filing on the portal leaves one CREATED entry by the customer; filing on behalf, one by the agent who filed")
    void filingLeavesOneCreatedEntry() {
        World w = flow.world();
        Instant filedAt = flow.tick();
        Issue own = fixtures.fileIssue(w.reporter(), w.code());
        Instant filedOnBehalfAt = flow.tick();
        Issue onBehalf = flow.fileOnBehalf(w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()));

        List<JsonNode> ofOwn = flow.history(w.agent(), own.key());
        List<JsonNode> ofOnBehalf = flow.history(w.admin(), onBehalf.key());

        assertThat(ofOwn).as("history of an issue filed on the portal").hasSize(1);
        assertCreated(ofOwn.getFirst(), w.reporter(), "CUSTOMER", filedAt);
        assertThat(ofOnBehalf).as("history of an issue filed on behalf").hasSize(1);
        assertCreated(ofOnBehalf.getFirst(), w.agent(), "AGENT", filedOnBehalfAt);
    }

    // S3-AC-62

    @Test
    @DisplayName("S3-AC-62 a change of the status, the assignee, the title, the content, and the priority each leave one CHANGED entry with the field, both values, who, and when")
    void eachChangeLeavesOneEntry() {
        World w = flow.world();
        String title = Unique.text("History title");
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title(title).content("First content."));
        String newTitle = Unique.text("History title, edited");

        Instant statusAt = flow.tick();
        flow.changeStatus(w.agent(), issue.key(), "CONFIRMED");
        Instant assignedAt = flow.tick();
        flow.assign(w.agent(), issue.key(), w.agent().accountId());
        Instant reassignedAt = flow.tick();
        flow.assign(w.admin(), issue.key(), w.admin().accountId());
        Instant clearedAt = flow.tick();
        flow.unassign(w.agent(), issue.key());
        Instant titleAt = flow.tick();
        flow.edit(w.agent(), issue.key(), Map.of("title", newTitle));
        Instant contentAt = flow.tick();
        flow.edit(w.admin(), issue.key(), Map.of("content", "Second content."));
        Instant priorityAt = flow.tick();
        flow.edit(w.agent(), issue.key(), Map.of("priority", "URGENT"));

        List<JsonNode> history = flow.history(w.admin(), issue.key());

        assertThat(history).as("history: %s", history).hasSize(8);
        assertThat(text(history.get(0), "kind")).isEqualTo("CREATED");
        assertChange(history.get(1), "STATUS", "RECEIVED", "CONFIRMED", w.agent(), "AGENT", statusAt);
        assertAssigneeChange(history.get(2), 0, w.agent().accountId(), w.agent(), "AGENT", assignedAt);
        assertThat(text(history.get(2).path("newPerson"), "name")).as("name of the new assignee")
                .isEqualTo(w.agent().name());
        assertThat(text(history.get(2).path("newPerson"), "role")).as("role of the new assignee").isEqualTo("AGENT");
        assertAssigneeChange(history.get(3), w.agent().accountId(), w.admin().accountId(), w.admin(), "ADMIN",
                reassignedAt);
        assertAssigneeChange(history.get(4), w.admin().accountId(), 0, w.agent(), "AGENT", clearedAt);
        assertChange(history.get(5), "TITLE", title, newTitle, w.agent(), "AGENT", titleAt);
        assertChange(history.get(6), "CONTENT", "First content.", "Second content.", w.admin(), "ADMIN", contentAt);
        assertChange(history.get(7), "PRIORITY", "NORMAL", "URGENT", w.agent(), "AGENT", priorityAt);
    }

    @Test
    @DisplayName("S3-AC-62 one edit of the title and the priority leaves two entries, and a reopening leaves one status entry")
    void twoFieldsLeaveTwoEntriesAndReopeningOne() {
        World w = flow.world();
        String title = Unique.text("Two fields");
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title(title).priority("LOW"));
        String newTitle = Unique.text("Two fields, edited");

        Instant editedAt = flow.tick();
        flow.edit(w.agent(), issue.key(), Map.of("title", newTitle, "priority", "HIGH"));

        List<JsonNode> afterEdit = flow.history(w.agent(), issue.key());
        assertThat(afterEdit).as("history after one edit of two fields: %s", afterEdit).hasSize(3);
        Map<String, JsonNode> byField = new LinkedHashMap<>();
        afterEdit.subList(1, 3).forEach(entry -> byField.put(text(entry, "field"), entry));
        assertThat(byField.keySet()).containsExactlyInAnyOrder("TITLE", "PRIORITY");
        assertChange(byField.get("TITLE"), "TITLE", title, newTitle, w.agent(), "AGENT", editedAt);
        assertChange(byField.get("PRIORITY"), "PRIORITY", "LOW", "HIGH", w.agent(), "AGENT", editedAt);

        flow.moveTo(w.agent(), issue.key(), "RESOLVED");
        Instant reopenedAt = flow.tick();
        flow.reopen(w.admin(), issue.key());

        List<JsonNode> afterReopening = flow.history(w.agent(), issue.key());
        assertThat(afterReopening).as("history after reopening: %s", afterReopening).hasSize(6);
        assertChange(afterReopening.get(5), "STATUS", "RESOLVED", "RECEIVED", w.admin(), "ADMIN", reopenedAt);
    }

    // S3-AC-63

    @Test
    @DisplayName("S3-AC-63 an edit to the same values, the same assignee again, a transition out of the table, a colleague's edit, and an old version leave no entry")
    void unchangedAndRefusedRequestsLeaveNoEntry() {
        World w = flow.world();
        String title = Unique.text("Unchanged");
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title(title).content("Same content.")
                .priority("HIGH"));
        flow.assign(w.agent(), issue.key(), w.agent().accountId());
        List<JsonNode> before = flow.history(w.admin(), issue.key());
        assertThat(before).as("history before the requests").hasSize(2);
        long version = flow.version(w.admin(), issue.key());
        flow.tick();
        Map<String, ApiResponse> answers = new LinkedHashMap<>();

        answers.put("200 same values", IssueFlow.editRequest(w.reporter(), issue.key(),
                Map.of("title", title, "content", "Same content.", "priority", "HIGH", "version", version)).send());
        answers.put("200 same assignee", IssueFlow.assignRequest(w.admin(), issue.key(), w.agent().accountId())
                .send());
        answers.put("400 transition out of the table", IssueFlow.statusRequest(w.agent(), issue.key(), "RESOLVED")
                .send());
        answers.put("403 colleague", IssueFlow.editRequest(w.colleague(), issue.key(),
                Map.of("title", Unique.text("By a colleague"), "version", version)).send());
        answers.put("409 old version", IssueFlow.editRequest(w.agent(), issue.key(),
                Map.of("title", Unique.text("With an old version"), "version", version - 1)).send());

        answers.forEach((what, answer) -> assertThat(answer.status()).as(what + ": " + answer.request() + " -> "
                + answer.snippet()).isEqualTo(Integer.parseInt(what.substring(0, 3))));
        assertThat(flow.history(w.admin(), issue.key())).as("history after the requests").isEqualTo(before);
    }

    // S3-AC-64

    @Test
    @DisplayName("S3-AC-64 the history comes oldest first in pages, and who did it has accountId, name, role, and photoUpdatedAt")
    void oldestFirstInPages() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        for (String status : List.of("CONFIRMED", "IN_PROGRESS", "RESOLVED", "CLOSED")) {
            flow.tick();
            flow.changeStatus(w.agent(), issue.key(), status);
        }

        ApiResponse first = w.agent().client().get(historyPath(issue.key())).query("size", 2).expect(200);
        ApiResponse second = w.agent().client().get(historyPath(issue.key())).query("size", 2).query("page", 2)
                .expect(200);
        ApiResponse third = w.agent().client().get(historyPath(issue.key())).query("size", 2).query("page", 3)
                .expect(200);

        assertThat(first.jsonLong("totalElements")).as("totalElements").isEqualTo(5);
        assertThat(first.jsonLong("totalPages")).as("totalPages").isEqualTo(3);
        List<JsonNode> all = new ArrayList<>(items(first));
        all.addAll(items(second));
        all.addAll(items(third));
        assertThat(List.of(items(first).size(), items(second).size(), items(third).size())).as("page sizes")
                .containsExactly(2, 2, 1);
        assertThat(all).extracting(entry -> text(entry, "kind") + " " + text(entry, "newValue"))
                .as("entries over the three pages").containsExactly("CREATED null", "CHANGED CONFIRMED",
                        "CHANGED IN_PROGRESS", "CHANGED RESOLVED", "CHANGED CLOSED");
        assertThat(all).extracting(entry -> time(entry, "createdAt")).as("times, oldest first").isSorted()
                .doesNotHaveDuplicates();
        assertThat(all).extracting(entry -> entry.path("id").asLong(-1)).as("ids").doesNotHaveDuplicates()
                .doesNotContain(-1L);
        assertThat(all).allSatisfy(entry -> assertThat(entry.path("actor").propertyNames())
                .as("fields of actor in %s", entry)
                .containsExactlyInAnyOrder("accountId", "name", "role", "photoUpdatedAt"));
        assertThat(all).allSatisfy(entry -> assertThat(entry.propertyNames()).as("fields of %s", entry)
                .containsExactlyInAnyOrder("id", "kind", "field", "oldValue", "newValue", "oldPerson", "newPerson",
                        "oldLink", "newLink", "actor", "createdAt"));
    }

    // S3-AC-65

    @Test
    @DisplayName("S3-AC-65 the portal has no history path: a customer gets 404 for it and for the console path on the portal host")
    void portalHasNoHistory() {
        World w = flow.world();
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        w.reporter().client().get("/api/portal/issues/" + issue.key()).expect(200);

        ApiResponse portalPath = w.reporter().client().get("/api/portal/issues/" + issue.key() + "/history").send();
        ApiResponse consolePath = w.reporter().client().get(historyPath(issue.key())).send();

        for (ApiResponse answer : List.of(portalPath, consolePath)) {
            assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(404);
            assertThat(answer.text()).as("body of " + answer.request()).doesNotContain("CREATED")
                    .doesNotContain("\"actor\"");
        }
    }

    // S3-AC-66

    @Test
    @DisplayName("S3-AC-66 a customer's edit, closing, and reopening are entries by that customer, with the role CUSTOMER")
    void customerIsTheActorOfTheirChanges() {
        World w = flow.world();
        String title = Unique.text("By the customer");
        Issue issue = fixtures.fileIssue(w.reporter(), NewIssue.in(w.code()).title(title));
        String newTitle = Unique.text("By the customer, edited");

        Instant editedAt = flow.tick();
        flow.edit(w.reporter(), issue.key(), Map.of("title", newTitle));
        Instant closedAt = flow.tick();
        flow.close(w.reporter(), issue.key());
        Instant reopenedAt = flow.tick();
        flow.reopen(w.reporter(), issue.key());

        List<JsonNode> history = flow.history(w.agent(), issue.key());
        assertThat(history).as("history: %s", history).hasSize(4);
        assertChange(history.get(1), "TITLE", title, newTitle, w.reporter(), "CUSTOMER", editedAt);
        assertChange(history.get(2), "STATUS", "RECEIVED", "CLOSED", w.reporter(), "CUSTOMER", closedAt);
        assertChange(history.get(3), "STATUS", "CLOSED", "RECEIVED", w.reporter(), "CUSTOMER", reopenedAt);
    }
}
