package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.issue.IssueTables.Holder;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The issue's {@code version} and its first response time. */
class IssueVersionTest extends BareAppTest {

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    private static JsonNode staffView(Session staff, String key) {
        return staff.client().get("/api/console/issues/" + key).expect(200).json();
    }

    private static long version(JsonNode issue) {
        JsonNode v = issue.path("version");
        assertThat(v.isIntegralNumber()).as("version is a number in %s", issue).isTrue();
        return v.asLong();
    }

    // S0-AC-45

    @Test
    @DisplayName("S0-AC-45 a new issue carries a version, the same in the response and on both apps")
    void newIssueCarriesVersion() {
        Scene s = steps.scene();

        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        long created = version(issue.json());
        assertThat(version(s.customer().client().get("/api/portal/issues/" + issue.key()).expect(200).json()))
                .isEqualTo(created);
        assertThat(version(staffView(s.admin(), issue.key()))).isEqualTo(created);
    }

    @Test
    @DisplayName("S0-AC-45 the version goes up by exactly 1 whenever the issue row changes, and stays when it does not")
    void versionFollowsRowChanges() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String title = text(issue.json(), "title");
        IssueTables tables = new IssueTables(db);
        Holder row = tables.find(s.company().legalName(), title).issue();

        Map<String, Consumer<String>> actions = new LinkedHashMap<>();
        actions.put("customer comment", key -> fixtures.comment(s.customer(), key, "From the customer"));
        actions.put("internal comment", key -> fixtures.internalComment(agent, key, "Between staff"));
        actions.put("first staff public comment", key -> fixtures.comment(agent, key, "First answer"));
        actions.put("second staff public comment", key -> fixtures.comment(s.admin(), key, "Second answer"));
        actions.put("attachment", key -> fixtures.attach(s.customer(), key, File.text("log.txt", "a log")));

        long version = version(staffView(agent, issue.key()));
        String rowVersion = tables.rowVersion(row, title);
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Consumer<String>> step : actions.entrySet()) {
            step.getValue().accept(issue.key());
            long nextVersion = version(staffView(agent, issue.key()));
            String nextRowVersion = tables.rowVersion(row, title);
            long expected = nextRowVersion.equals(rowVersion) ? 0 : 1;
            assertThat(nextVersion - version)
                    .as("version change after the %s (row %s)", step.getKey(), expected == 1 ? "changed" : "unchanged")
                    .isEqualTo(expected);
            deltas.put(step.getKey(), nextVersion - version);
            version = nextVersion;
            rowVersion = nextRowVersion;
        }
        assertThat(deltas.get("first staff public comment"))
                .as("the first staff public comment writes the first response time into the row")
                .isEqualTo(1);
    }

    // S0-AC-46

    @Test
    @DisplayName("S0-AC-46 the first public staff comment sets the first response time to its time")
    void firstPublicStaffCommentSetsFirstResponse() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        assertThat(staffView(agent, issue.key()).path("firstResponseAt").isNull()).isTrue();
        Instant now = steps.tick();

        Comment answer = fixtures.comment(agent, issue.key(), "We are on it.");

        String firstResponse = text(staffView(agent, issue.key()), "firstResponseAt");
        assertThat(firstResponse).isNotNull();
        assertThat(Instant.parse(firstResponse)).isEqualTo(Instant.parse(text(answer.json(), "createdAt")));
        assertThat(Instant.parse(firstResponse)).isEqualTo(now);
    }

    @Test
    @DisplayName("S0-AC-46 customer comments and internal comments leave the first response time null")
    void customerAndInternalCommentsDoNotCount() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        fixtures.comment(s.customer(), issue.key(), "Any news?");
        fixtures.internalComment(agent, issue.key(), "Looking into it.");
        fixtures.internalComment(s.admin(), issue.key(), "Agreed.");

        JsonNode view = staffView(agent, issue.key());
        assertThat(view.has("firstResponseAt")).as("field present (D-44)").isTrue();
        assertThat(view.path("firstResponseAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("S0-AC-46 a later public staff comment does not change the first response time")
    void secondStaffCommentKeepsFirstResponse() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Instant first = steps.tick();
        fixtures.comment(agent, issue.key(), "First answer.");
        steps.tick();

        fixtures.comment(s.admin(), issue.key(), "Second answer.");
        fixtures.comment(agent, issue.key(), "Third answer.");

        assertThat(Instant.parse(text(staffView(agent, issue.key()), "firstResponseAt"))).isEqualTo(first);
    }

    @Test
    @DisplayName("S0-AC-46 the first response time is in staff responses only, not in the customer's")
    void firstResponseIsNotInPortalResponses() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        fixtures.comment(agent, issue.key(), "Answer.");

        JsonNode detail = s.customer().client().get("/api/portal/issues/" + issue.key()).expect(200).json();
        JsonNode listed = items(s.customer().client().get("/api/portal/issues").expect(200)).getFirst();

        assertThat(text(staffView(agent, issue.key()), "firstResponseAt")).isNotNull();
        assertThat(text(detail, "key")).isEqualTo(issue.key());
        assertThat(detail.propertyNames()).doesNotContain("firstResponseAt");
        assertThat(listed.propertyNames()).doesNotContain("firstResponseAt");
    }
}
