package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An upload onto an issue that loses against the attachment cleanup: the cleanup ends the file's
 * {@code pending} row while step ② is still writing its content. The cleanup works from
 * {@code pending} and {@code purging} rows only and never comes back to a row it ended, so the content
 * that step ② wrote afterwards must go with the failed upload. {@link FilingCleanupRaceTest} checks
 * the same for a file sent with a new issue.
 *
 * <p>The race is made as in {@link AttachmentCleanupTest}: a {@code test_} trigger holds step ② until
 * the test releases it ({@link Hold}), and the clock moves 25 hours so the cleanup runs meanwhile.
 */
class UploadCleanupRaceTest extends BareAppTest {

    private static final Duration PAST_A_DAY = Duration.ofHours(25);

    /** The cleanup wakes every few seconds of real time; allow a few periods. */
    private static final Duration CLEANUP = Duration.ofSeconds(30);

    @Autowired
    PostgreSQLContainer postgres;

    private AttachmentSteps steps;
    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("S0-AC-63 an upload whose pending row the cleanup ended while step ② wrote its file fails and leaves no content of that file")
    void uploadThatLostToTheCleanupLeavesNoContent() throws Exception {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = Unique.text("upload-race") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse finished;
        String id;
        try (FailureTriggers triggers = new FailureTriggers(db); Hold hold = new Hold(db, postgres)) {
            triggers.holdWritingContent(content, hold);
            CompletableFuture<ApiResponse> running =
                    CompletableFuture.supplyAsync(() -> upload(s.customer(), issue.key(), name, content));
            Hold.awaitHeld(db, "step ② of the upload");
            List<Row> pending = rows.byFileName(name);
            assertThat(pending).as("rows for %s while step ② is held", name).extracting(Row::state)
                    .containsExactly("pending");
            id = pending.getFirst().id();
            clock.advance(PAST_A_DAY);
            Wait.untilAsserted("the cleanup ended the pending row while step ② is held", CLEANUP,
                    () -> assertThat(rows.findById(id).map(Row::state)).satisfiesAnyOf(
                            state -> assertThat(state).isEmpty(),
                            state -> assertThat(state).contains("purged")));
            hold.release();
            finished = finish(running);
        }

        assertThat(finished.status()).as("the upload after the cleanup ended its row: %s %s", finished,
                finished.snippet()).isGreaterThanOrEqualTo(400);
        assertThat(rows.findById(id).map(Row::state).filter(state -> state.equals("stored")))
                .as("a stored row for %s", name).isEmpty();
        assertThat(rows.contentCopies(content))
                .as("rows of attachment_content with the content of %s, whose row the cleanup ended", name).isZero();
    }

    private static ApiResponse finish(CompletableFuture<ApiResponse> running) throws InterruptedException {
        try {
            return running.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError a) {
                throw a;
            }
            throw new AssertionError("The upload failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The upload did not finish in 60 seconds", e);
        }
    }
}
