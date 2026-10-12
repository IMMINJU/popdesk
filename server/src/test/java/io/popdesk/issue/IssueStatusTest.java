package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.STATUSES;
import static io.popdesk.issue.IssueFlow.statusRequest;
import static io.popdesk.issue.IssueFlow.time;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@code PUT /api/console/issues/{key}/status}: the transition table of staff ({@code rules.md} 2절),
 * the times a transition leaves, and the list of transitions the console response gives.
 */
class IssueStatusTest extends BareAppTest {

    /** The table: from each status, the statuses staff may set. Eight cells in all. */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "RECEIVED", List.of("CONFIRMED", "IN_PROGRESS", "CLOSED"),
            "CONFIRMED", List.of("IN_PROGRESS", "CLOSED"),
            "IN_PROGRESS", List.of("RESOLVED"),
            "RESOLVED", List.of("IN_PROGRESS", "CLOSED"),
            "CLOSED", List.of());

    private IssueSteps steps;
    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
        flow = new IssueFlow(fixtures, clock);
    }

    /** The 25 cells: the status now, the status asked for. */
    static Stream<Arguments> cells() {
        List<Arguments> out = new ArrayList<>();
        for (String from : STATUSES) {
            for (String to : STATUSES) {
                out.add(Arguments.of(from, to));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cells")
    @DisplayName("S3-AC-48, S3-AC-49 the eight transitions of the table are stored with the version raised by 1; the other seventeen are 400 INVALID_TRANSITION and change nothing")
    void transitionTable(String from, String to) {
        Scene s = steps.scene();
        Issue issue = flow.fileIn(s.customer(), NewIssue.in(s.code()), s.admin(), from);
        JsonNode before = flow.detail(s.admin(), issue.key());
        assertThat(text(before, "status")).as("status before the request").isEqualTo(from);
        flow.tick();

        ApiResponse answer = statusRequest(s.admin(), issue.key(), to).send();

        JsonNode after = flow.detail(s.admin(), issue.key());
        if (ALLOWED.get(from).contains(to)) {
            assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(200);
            assertThat(text(answer.json(), "status")).as("status in the response").isEqualTo(to);
            assertThat(answer.json().path("version").asLong(-1)).as("version in the response")
                    .isEqualTo(before.path("version").asLong(-2) + 1);
            assertThat(text(after, "status")).as("status read again").isEqualTo(to);
            assertThat(after.path("version").asLong(-1)).as("version read again")
                    .isEqualTo(before.path("version").asLong(-2) + 1);
        } else {
            assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(400);
            assertThat(answer.errorCode()).isEqualTo("INVALID_TRANSITION");
            assertThat(after).as("the issue after the refused transition").isEqualTo(before);
        }
    }

    @Test
    @DisplayName("S3-AC-49 a status name that does not exist is 400 INVALID_INPUT with the field status, and nothing changes")
    void unknownStatusName() {
        Scene s = steps.scene();
        Issue issue = flow.fileIn(s.customer(), NewIssue.in(s.code()), s.admin(), "CONFIRMED");
        JsonNode before = flow.detail(s.admin(), issue.key());

        ApiResponse answer = statusRequest(s.admin(), issue.key(), "DONE").send();

        assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(400);
        assertThat(answer.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(answer)).contains("status");
        assertThat(flow.detail(s.admin(), issue.key())).as("the issue after the refused request").isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RECEIVED", "CONFIRMED", "IN_PROGRESS", "RESOLVED", "CLOSED"})
    @DisplayName("S3-AC-59 the console issue lists the transitions of the table for its status, and both apps say whether it can be reopened")
    void nextStatusesFollowTheTable(String status) {
        Scene s = steps.scene();
        Issue issue = flow.fileIn(s.customer(), NewIssue.in(s.code()), s.admin(), status);
        boolean reopenable = status.equals("RESOLVED") || status.equals("CLOSED");

        JsonNode staffView = flow.detail(s.admin(), issue.key());
        JsonNode customerView = flow.detail(s.customer(), issue.key());

        assertThat(text(staffView, "status")).isEqualTo(status);
        assertThat(staffView.path("nextStatuses").isArray()).as("nextStatuses is a list in %s", staffView).isTrue();
        assertThat(staffView.path("nextStatuses").values()).extracting(n -> n.asString(null))
                .as("nextStatuses of %s", status).containsExactlyInAnyOrderElementsOf(ALLOWED.get(status));
        assertThat(staffView.path("reopenable").isBoolean()).as("reopenable is a flag in %s", staffView).isTrue();
        assertThat(staffView.path("reopenable").booleanValue()).as("console reopenable of %s", status)
                .isEqualTo(reopenable);
        assertThat(customerView.path("reopenable").isBoolean()).as("reopenable is a flag in %s", customerView)
                .isTrue();
        assertThat(customerView.path("reopenable").booleanValue()).as("portal reopenable of %s", status)
                .isEqualTo(reopenable);
    }

    @Test
    @DisplayName("S3-AC-50, S3-AC-59 RESOLVED leaves the resolved time, going back to IN_PROGRESS clears it, CLOSED leaves the closed time and keeps the resolved time, on both apps")
    void resolvedAndClosedTimes() {
        Scene s = steps.scene();
        Issue issue = flow.fileIn(s.customer(), NewIssue.in(s.code()), s.admin(), "IN_PROGRESS");
        assertTimes("IN_PROGRESS", s, issue.key(), null, null);

        Instant firstResolved = flow.tick();
        JsonNode resolved = flow.changeStatus(s.admin(), issue.key(), "RESOLVED");

        assertThat(time(resolved, "resolvedAt")).as("resolvedAt in the response").isEqualTo(firstResolved);
        assertTimes("RESOLVED", s, issue.key(), firstResolved, null);

        flow.tick();
        flow.changeStatus(s.admin(), issue.key(), "IN_PROGRESS");

        assertTimes("IN_PROGRESS again", s, issue.key(), null, null);

        Instant secondResolved = flow.tick();
        flow.changeStatus(s.admin(), issue.key(), "RESOLVED");
        Instant closed = flow.tick();
        JsonNode closedIssue = flow.changeStatus(s.admin(), issue.key(), "CLOSED");

        assertThat(time(closedIssue, "closedAt")).as("closedAt in the response").isEqualTo(closed);
        assertTimes("CLOSED after RESOLVED", s, issue.key(), secondResolved, closed);
    }

    @Test
    @DisplayName("S3-AC-50 an issue closed from RECEIVED has a closed time and no resolved time")
    void closedWithoutResolving() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        Instant closed = flow.tick();
        flow.changeStatus(s.admin(), issue.key(), "CLOSED");

        assertTimes("CLOSED from RECEIVED", s, issue.key(), null, closed);
    }

    /** Both apps give these two times for the issue; null means the field is there and null. */
    private void assertTimes(String when, Scene s, String key, Instant resolvedAt, Instant closedAt) {
        JsonNode staffView = flow.detail(s.admin(), key);
        JsonNode customerView = flow.detail(s.customer(), key);
        for (JsonNode view : List.of(staffView, customerView)) {
            assertThat(view.has("resolvedAt") && view.has("closedAt"))
                    .as("%s: resolvedAt and closedAt are fields of %s", when, view).isTrue();
            assertThat(time(view, "resolvedAt")).as("%s: resolvedAt in %s", when, view).isEqualTo(resolvedAt);
            assertThat(time(view, "closedAt")).as("%s: closedAt in %s", when, view).isEqualTo(closedAt);
        }
    }
}
