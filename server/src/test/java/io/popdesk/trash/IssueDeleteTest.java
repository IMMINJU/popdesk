package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Deleting an issue ({@code DELETE /api/console/issues/{key}}, TRASH-1): an admin moves it to the trash in any
 * status and nothing under it changes (S7A-AC-01), nobody else may (S7A-AC-02), and the issue's history gets a
 * {@code TRASH} entry and its version goes up (S7A-AC-06).
 *
 * <p>While the issue is in the trash every issue path is 404 and restoring is another test's, so what is under the
 * issue is read from the database ({@link TrashRows}), and the time and person of the delete from the trash list.
 */
class IssueDeleteTest extends BareAppTest {

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RECEIVED", "CONFIRMED", "IN_PROGRESS", "RESOLVED", "CLOSED"})
    @DisplayName("S7A-AC-01 an admin deletes an issue in any status: 204, the trash lists it with the time and the admin, and its fields, comments, files, task and note, link, label, assignee, and due date are as they were")
    void adminDeletesInAnyStatus(String status) {
        TrashScene s = TrashScene.build(fixtures, flow, clock, status);
        Map<String, Object> fields = rows.fields(s.key());
        Map<String, List<Map<String, Object>>> under = rows.under(s.key());
        Instant at = flow.tick();

        ApiResponse answer = TrashSteps.deleteRequest(s.admin(), s.key()).send();

        assertThat(answer.status()).as("delete: " + answer.snippet()).isEqualTo(204);
        assertThat(answer.body()).as("body of the 204").isEmpty();
        JsonNode entry = TrashSteps.entry(s.admin(), s.key());
        assertThat(entry).as("trash entry of " + s.key()).isNotNull();
        assertThat(entry.path("status").asString(null)).as("status in the trash").isEqualTo(status);
        assertThat(entry.path("title").asString(null)).as("title in the trash").isEqualTo(s.title());
        assertThat(time(entry, "deletedAt")).as("deletedAt").isEqualTo(at);
        assertThat(entry.path("deletedBy").path("accountId").asLong(-1)).as("deletedBy")
                .isEqualTo(s.admin().accountId());
        assertThat(rows.fields(s.key())).as("the issue's own fields after the delete").isEqualTo(fields);
        assertThat(rows.under(s.key())).as("what is under the issue after the delete").isEqualTo(under);
        assertThat(under.get("comments")).as("comments made by the scene").hasSize(3);
        assertThat(under.get("attachments")).as("files made by the scene").hasSize(2);
        assertThat(under.get("links")).as("links made by the scene").hasSize(1);
    }

    @Test
    @DisplayName("S7A-AC-02 an agent assigned to the company gets 403 FORBIDDEN, the reporting customer 404 on the portal host and 401 on the console host; the issue stays as it was and out of the trash")
    void onlyAdminsDelete() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        JsonNode before = s.detail(flow);
        long version = rows.version(s.key());
        ApiClient onConsole = console();
        s.reporter().client().cookies().forEach(onConsole::setCookie);

        ApiResponse byAgent = TrashSteps.deleteRequest(s.agent(), s.key()).send();
        ApiResponse onPortalHost = s.reporter().client().delete(TrashSteps.issuePath(s.key())).send();
        ApiResponse onConsoleHost = onConsole.delete(TrashSteps.issuePath(s.key())).send();

        assertThat(byAgent.status()).as("agent: " + byAgent.snippet()).isEqualTo(403);
        assertThat(byAgent.errorCode()).as("code for the agent").isEqualTo("FORBIDDEN");
        assertThat(onPortalHost.status()).as("customer on the portal host: " + onPortalHost.snippet())
                .isEqualTo(404);
        assertThat(onConsoleHost.status()).as("customer on the console host: " + onConsoleHost.snippet())
                .isEqualTo(401);
        assertThat(s.detail(flow)).as("the issue after the refused deletes").isEqualTo(before);
        assertThat(rows.version(s.key())).as("version after the refused deletes").isEqualTo(version);
        TrashSteps.delete(s.admin(), s.other().key());
        assertThat(TrashSteps.keys(s.admin())).as("the trash, where the admin's delete of the other issue is")
                .contains(s.other().key())
                .doesNotContain(s.key());
    }

    @Test
    @DisplayName("S7A-AC-06 deleting adds one TRASH entry to the issue's history, from null to TRASHED, by the admin at the time of the delete, and raises the version by 1")
    void deleteLeavesATrashEntry() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "IN_PROGRESS");
        long version = rows.version(s.key());
        int entries = rows.history(s.key()).size();
        Instant at = flow.tick();

        TrashSteps.delete(s.admin(), s.key());

        List<Map<String, Object>> history = rows.history(s.key());
        assertThat(history).as("history rows after the delete").hasSize(entries + 1);
        Map<String, Object> last = history.getLast();
        assertThat(last.get("field")).as("field of " + last).isEqualTo(TrashSteps.FIELD);
        assertThat(last.get("old_value")).as("old value of " + last).isNull();
        assertThat(last.get("new_value")).as("new value of " + last).isEqualTo(TrashSteps.TRASHED);
        assertThat(((Number) last.get("actor_id")).longValue()).as("actor of " + last)
                .isEqualTo(s.admin().accountId());
        assertThat(TrashRows.instant(last.get("created_at"))).as("time of " + last).isEqualTo(at);
        assertThat(rows.version(s.key())).as("version after the delete").isEqualTo(version + 1);
    }
}
