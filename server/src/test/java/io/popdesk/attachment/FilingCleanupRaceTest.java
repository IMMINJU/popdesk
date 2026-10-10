package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * A filing with a file that loses against the attachment cleanup (verify.md V-9): the cleanup ends
 * the file's {@code pending} row while step ② is still writing its content, so the filing transaction
 * (step ③) fails. The cleanup works from {@code pending} and {@code purging} rows only and never
 * comes back to a row it ended, so the content that step ② wrote afterwards must go with the failed
 * filing.
 *
 * <p>The race is made as in {@link AttachmentCleanupTest}: a {@code test_} trigger holds step ② until
 * the test releases it ({@link Hold}), and the clock moves 25 hours so the cleanup runs meanwhile.
 */
class FilingCleanupRaceTest extends BareAppTest {

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
    @DisplayName("V-9 S0-AC-63 a filing whose pending file the cleanup ended while step ② wrote it fails and leaves no content of that file")
    void filingThatLostToTheCleanupLeavesNoContent() throws Exception {
        Scene s = steps.scene();
        String title = Unique.text("Lost to the cleanup");
        String name = Unique.text("filing-race") + ".bin";
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse finished;
        String id;
        try (FailureTriggers triggers = new FailureTriggers(db); Hold hold = new Hold(db, postgres)) {
            triggers.holdWritingContent(content, hold);
            CompletableFuture<ApiResponse> running =
                    CompletableFuture.supplyAsync(() -> fileWithFile(s, title, name, content));
            Hold.awaitHeld(db, "step ② of the filing");
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

        assertThat(finished.status()).as("the filing after the cleanup ended its file's row: %s %s", finished,
                finished.snippet()).isGreaterThanOrEqualTo(400);
        assertThat(rows.findById(id).map(Row::state).filter(state -> state.equals("stored")))
                .as("a stored row for %s", name).isEmpty();
        Session again = fixtures.signInAgain(s.customer());
        assertThat(again.client().get("/api/portal/issues").query("size", 100).expect(200).json().path("items")
                .findValuesAsString("title")).as("titles of the customer's issues").doesNotContain(title);
        assertThat(rows.contentCopies(content))
                .as("rows of attachment_content with the content of %s, whose row the cleanup ended", name).isZero();
    }

    private static ApiResponse fileWithFile(Scene s, String title, String fileName, byte[] content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", title);
        return s.customer().client().post("/api/portal/issues").jsonPart("issue", body)
                .file("files", fileName, "application/octet-stream", content).send();
    }

    private static ApiResponse finish(CompletableFuture<ApiResponse> running) throws InterruptedException {
        try {
            return running.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError a) {
                throw a;
            }
            throw new AssertionError("The filing failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The filing did not finish in 60 seconds", e);
        }
    }
}
