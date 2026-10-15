package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.task.TaskSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Restoring an issue from the trash ({@code POST /api/console/trash/{key}/restore}, TRASH-2): everything is as it was
 * before the delete (S7A-AC-21), and a {@code RESOLVED} issue closes 7 days after the restore, not 7 days after it
 * was resolved (S7A-AC-22).
 */
class IssueRestoreTest extends BareAppTest {

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    /** The issue as staff see it, without what a delete and a restore change: {@code version}, {@code updatedAt}. */
    private static JsonNode lasting(JsonNode issue) {
        ObjectNode copy = (ObjectNode) issue.deepCopy();
        copy.remove("version");
        copy.remove("updatedAt");
        return copy;
    }

    private static List<String> described(List<JsonNode> entries) {
        return entries.stream().map(e -> LinkSteps.historyLink(e, "oldLink") + " -> "
                + LinkSteps.historyLink(e, "newLink")).toList();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CONFIRMED", "RESOLVED", "CLOSED"})
    @DisplayName("S7A-AC-21 restoring answers 200 with the console issue, and its fields, status, assignee, due date, labels, comments, files, task and note, the link seen from the other issue, and the reporter's inbox items are as before the delete")
    void restoreBringsBackWhatWasThere(String status) {
        TrashScene s = TrashScene.build(fixtures, flow, clock, status);
        String key = s.key();
        String other = s.other().key();
        JsonNode detail = s.detail(flow);
        Map<String, Object> fields = rows.fields(key);
        Map<String, List<Map<String, Object>>> under = rows.under(key);
        List<JsonNode> staffComments = TalkFlow.comments(s.agent(), key);
        List<JsonNode> customerComments = TalkFlow.comments(s.reporter(), key);
        List<JsonNode> tasks = TaskSteps.tasks(s.agent(), key);
        List<String> linkedFromOther = LinkSteps.linkedKeys(s.agent(), other);
        List<String> linkHistoryOfOther = described(LinkSteps.linkEntries(s.agent(), other));
        List<Long> reporterItems = TalkFlow.ids(TalkFlow.inboxOf(s.reporter(), key));
        assertThat(linkedFromOther).as("issues linked to " + other + " before the delete").contains(key);
        assertThat(reporterItems).as("the reporter's inbox items about " + key + " before the delete").isNotEmpty();
        TrashSteps.delete(s.admin(), key);
        flow.tick();

        JsonNode restored = TrashSteps.restore(s.admin(), key);

        JsonNode after = s.detail(flow);
        assertThat(restored).as("the answer of the restore, against the issue read after it").isEqualTo(after);
        assertThat(lasting(after)).as("the issue after the restore, without version and updatedAt")
                .isEqualTo(lasting(detail));
        assertThat(after.path("status").asString(null)).as("status after the restore").isEqualTo(status);
        assertThat(rows.fields(key)).as("the issue's own fields after the restore").isEqualTo(fields);
        assertThat(rows.under(key)).as("what is under the issue after the restore").isEqualTo(under);
        assertThat(TalkFlow.comments(s.agent(), key)).as("comments staff see").isEqualTo(staffComments);
        assertThat(TalkFlow.comments(s.reporter(), key)).as("comments the customer sees").isEqualTo(customerComments);
        assertThat(TaskSteps.tasks(s.agent(), key)).as("tasks").isEqualTo(tasks);
        assertThat(LinkSteps.linkedKeys(s.agent(), other)).as("issues linked to " + other)
                .isEqualTo(linkedFromOther);
        assertThat(described(LinkSteps.linkEntries(s.agent(), other))).as("LINK history of " + other)
                .isEqualTo(linkHistoryOfOther);
        assertThat(TalkFlow.ids(TalkFlow.inboxOf(s.reporter(), key))).as("the reporter's inbox items about " + key)
                .isEqualTo(reporterItems);
        assertThat(s.reporter().client().get("/api/portal/issues/" + key + "/attachments/" + s.issueFile().id())
                .expect(200).body()).as("the customer's file").isEqualTo(s.issueFile().content());
        assertThat(s.agent().client().get("/api/console/issues/" + key + "/attachments/" + s.commentFile().id())
                .expect(200).body()).as("the file of the staff comment").isEqualTo(s.commentFile().content());
        assertThat(TrashSteps.entry(s.admin(), key)).as("trash entry after the restore").isNull();
    }

    @Test
    @DisplayName("S7A-AC-22 a RESOLVED issue restored 6 days after it was resolved stays RESOLVED until 7 days after the restore and is closed after that; resolvedAt does not change")
    void restoredResolvedIssueClosesSevenDaysAfterTheRestore() {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "RESOLVED");
        String key = issue.key();
        Instant resolvedAt = time(flow.detail(w.agent(), key), "resolvedAt");
        TrashSteps.delete(w.admin(), key);
        clock.advance(Duration.ofDays(6));
        Session admin = fixtures.signInAgain(w.admin());
        flow.tick();
        TrashSteps.restore(admin, key);

        Instant almost = clock.advance(Duration.ofDays(7).minusMinutes(1));
        admin = fixtures.signInAgain(admin);
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, almost);
        JsonNode before = flow.detail(admin, key);
        assertThat(before.path("status").asString(null)).as("status a minute before 7 days after the restore")
                .isEqualTo("RESOLVED");
        assertThat(time(before, "resolvedAt")).as("resolvedAt after the restore").isEqualTo(resolvedAt);

        Instant due = clock.advance(Duration.ofMinutes(11));
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, due, 1);
        JsonNode closed = flow.detail(admin, key);
        assertThat(closed.path("status").asString(null)).as("status 10 minutes after 7 days after the restore")
                .isEqualTo("CLOSED");
        assertThat(time(closed, "closedAt")).as("closedAt").isEqualTo(due);
    }
}
