package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.statusRequest;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Status changes of one issue sent at the same time (STATUS-7): the answers and what is left are
 * those of the requests handled one after another, in some order. With the team webhook on (setup
 * "connected"), so that a transition stored twice would also show as two webhook requests.
 */
class IssueStatusRaceTest extends ConnectedAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** The status entries of the history as {@code OLD>NEW}, oldest first. */
    private List<String> statusEntries(Session staff, String key) {
        return flow.history(staff, key).stream()
                .filter(entry -> "STATUS".equals(text(entry, "field")))
                .map(entry -> text(entry, "oldValue") + ">" + text(entry, "newValue"))
                .toList();
    }

    @Test
    @DisplayName("S3-AC-51 of eight requests for the same transition at once one is 200 and seven are 400, with one history entry and one webhook")
    void sameTransitionAtOnceHappensOnce() {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "IN_PROGRESS");
        long version = flow.version(w.agent(), issue.key());
        Wait.until("the webhook of the new issue " + issue.key(),
                () -> webhook().containing(issue.key()).size() == 1 ? Boolean.TRUE : null);
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> statusRequest(w.agent(), issue.key(), "RESOLVED").send());
            calls.add(() -> statusRequest(w.admin(), issue.key(), "RESOLVED").send());
        }

        List<ApiResponse> answers = Concurrently.send(8, calls);

        assertThat(answers).extracting(ApiResponse::status).as("statuses of the eight requests")
                .containsExactlyInAnyOrder(200, 400, 400, 400, 400, 400, 400, 400);
        assertThat(answers.stream().filter(r -> r.status() == 400)).as("the refused requests")
                .allSatisfy(r -> assertThat(r.errorCode()).isEqualTo("INVALID_TRANSITION"));
        JsonNode after = flow.detail(w.agent(), issue.key());
        assertThat(text(after, "status")).isEqualTo("RESOLVED");
        assertThat(after.path("version").asLong(-1)).as("version after one transition").isEqualTo(version + 1);
        assertThat(statusEntries(w.agent(), issue.key())).as("status entries of the history")
                .containsExactly("RECEIVED>IN_PROGRESS", "IN_PROGRESS>RESOLVED");
        Wait.until("the webhook of the resolved issue " + issue.key(),
                () -> webhook().containing(issue.key()).size() >= 2 ? Boolean.TRUE : null);
        Wait.holds("two webhooks for " + issue.key() + ": the new issue and the one transition",
                Duration.ofSeconds(3), () -> webhook().containing(issue.key()).size() == 2);
    }

    @Test
    @DisplayName("S3-AC-51 RECEIVED to CONFIRMED and RECEIVED to CLOSED at once end as one of the two orders would: closed, with the history of that order")
    void twoTransitionsAtOnceEndAsSomeOrderWould() {
        World w = flow.world();
        // A status change must work before the race means anything.
        flow.changeStatus(w.agent(), fixtures.fileIssue(w.reporter(), w.code()).key(), "CONFIRMED");

        for (int round = 0; round < 5; round++) {
            Issue issue = fixtures.fileIssue(w.reporter(), w.code());
            long version = flow.version(w.agent(), issue.key());

            List<ApiResponse> answers = Concurrently.send(2, List.of(
                    () -> statusRequest(w.agent(), issue.key(), "CONFIRMED").send(),
                    () -> statusRequest(w.admin(), issue.key(), "CLOSED").send()));

            ApiResponse confirm = answers.get(0);
            ApiResponse close = answers.get(1);
            assertThat(close.status()).as("closing is in the table from RECEIVED and from CONFIRMED: " + close.snippet())
                    .isEqualTo(200);
            JsonNode after = flow.detail(w.agent(), issue.key());
            assertThat(text(after, "status")).as("status after both requests").isEqualTo("CLOSED");
            List<String> entries = statusEntries(w.agent(), issue.key());
            if (confirm.status() == 200) {
                assertThat(entries).as("history when confirming came first")
                        .containsExactly("RECEIVED>CONFIRMED", "CONFIRMED>CLOSED");
                assertThat(after.path("version").asLong(-1)).isEqualTo(version + 2);
            } else {
                assertThat(confirm.status()).as("confirming after closing: " + confirm.snippet()).isEqualTo(400);
                assertThat(confirm.errorCode()).isEqualTo("INVALID_TRANSITION");
                assertThat(entries).as("history when closing came first").containsExactly("RECEIVED>CLOSED");
                assertThat(after.path("version").asLong(-1)).isEqualTo(version + 1);
            }
        }
    }
}
