package io.popdesk.issue;

import static io.popdesk.issue.IssueFlow.time;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.label.LabelSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Adding a label while the issue is being closed (S6A-AC-23, STATUS-7), the way {@link IssueCloseRaceTest}
 * makes the two overlap: the closing request is kept waiting at its UPDATE of the issue row ({@link HeldWrite}),
 * the clock moves a second, the label is added, and the closing is let go once the adding has finished or
 * waits for a lock.
 *
 * <p>Which request wins is not fixed. A label that is on the closed issue must have been added before the
 * closing: its history entry is not later than {@code closedAt} and comes before the closing entry. An adding
 * that was refused is 400 {@code ISSUE_CLOSED} and left no label and no entry. An adding that does not lock the
 * issue row reads the status before the closing is done, so it is stored a second after the closing.
 */
class LabelCloseRaceTest extends BareAppTest {

    /** How long the closing request is held when the adding neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    @Test
    @DisplayName("S6A-AC-23 a label added while the issue is closed is on the closed issue only if it was added before the closing; otherwise the adding is 400 ISSUE_CLOSED and the issue has no label")
    void addingOverlapsTheClosing() {
        World w = flow.world();
        String title = Unique.text("Label race");
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(title), w.agent(), "CONFIRMED");
        String issueTable = new IssueTables(db).find(w.company().legalName(), title).issue().table().name();
        JsonNode label = LabelSteps.create(w.admin(), w.code());
        ApiResponse closing;
        ApiResponse adding;

        try (HeldWrite hold = new HeldWrite(db, postgres, issueTable, "update", title)) {
            CompletableFuture<ApiResponse> close = CompletableFuture.supplyAsync(
                    () -> IssueFlow.closeRequest(w.reporter(), issue.key()).send());
            hold.awaitHeld("the UPDATE that closes the issue");
            flow.tick();
            CompletableFuture<ApiResponse> add = CompletableFuture.supplyAsync(
                    () -> LabelSteps.addRequest(w.agent(), issue.key(), LabelSteps.id(label)).send());
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the adding finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> add.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            closing = Concurrently.finish(close, "the closing request");
            adding = Concurrently.finish(add, "the adding");
        }

        assertThat(closing.status()).as("closing: " + closing.snippet()).isEqualTo(200);
        assertThat(adding.status()).as("adding: " + adding.snippet()).isLessThan(500);
        JsonNode after = flow.detail(w.agent(), issue.key());
        assertThat(text(after, "status")).as("status after both requests").isEqualTo("CLOSED");
        Instant closedAt = time(after, "closedAt");
        assertThat(closedAt).as("closedAt after both requests").isNotNull();
        boolean stored = adding.status() == 200;
        if (!stored) {
            assertThat(adding.status()).as("adding after the closing: " + adding.snippet()).isEqualTo(400);
            assertThat(adding.errorCode()).as("code of the refused adding").isEqualTo("ISSUE_CLOSED");
        }
        assertThat(LabelSteps.labelIds(after)).as("labels of the closed issue")
                .isEqualTo(stored ? List.of(LabelSteps.id(label)) : List.of());
        List<JsonNode> entries = flow.history(w.agent(), issue.key()).stream()
                .filter(e -> "LABEL".equals(text(e, "field")) || "CLOSED".equals(text(e, "newValue"))).toList();
        assertThat(entries.stream().map(e -> text(e, "field")).toList())
                .as("the label entry and the closing entry of the history, in order")
                .isEqualTo(stored ? List.of("LABEL", "STATUS") : List.of("STATUS"));
        if (stored) {
            assertThat(time(entries.getFirst(), "createdAt")).as("time of the label entry, not after the closing")
                    .isBeforeOrEqualTo(closedAt);
        }
    }
}
