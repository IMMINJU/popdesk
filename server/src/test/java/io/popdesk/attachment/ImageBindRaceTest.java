package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.ImageSteps.inlineIds;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two things that want the same temporary image at the same time: two requests that attach it, and a
 * request that attaches it while the cleanup deletes it. They are made to overlap in the database
 * ({@link HeldWrite}): the first request is kept waiting at its UPDATE of the image's row, with the
 * row locked, the other side is started, and the first is let go once the other has finished, waits
 * for a lock, or had a few seconds.
 *
 * <p>Which side wins is not fixed. What is left must be what one of the two orders leaves: the image
 * is attached once or not at all, an attached image can be read, and a request that lost stored
 * nothing.
 */
class ImageBindRaceTest extends BareAppTest {

    /** How long the first request is held when the other side neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(4);

    @Autowired
    PostgreSQLContainer postgres;

    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        rows = new AttachmentRows(db);
    }

    private static ApiResponse comment(Session session, String key, String content, String imageId) {
        return TalkFlow.addRequest(session, key, body("content", content, "imageIds", List.of(imageId))).send();
    }

    private static boolean hasComment(Session session, String key, String content) {
        return TalkFlow.comments(session, key).stream().anyMatch(c -> content.equals(text(c, "content")));
    }

    private static void awaitTheOtherSide(HeldWrite hold, CompletableFuture<?> other) {
        long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
        Wait.until("the other side finished, waits for a lock, or had " + HELD_AT_MOST,
                () -> (other != null && other.isDone()) || hold.waitingForALock() > 0
                        || System.nanoTime() > heldUntil ? Boolean.TRUE : null);
    }

    @Test
    @DisplayName("S5-AC-22 two comments on two issues that name the same image at the same time: one is stored with "
            + "the image, the other is 400 TEMPORARY_IMAGE_INVALID and stores nothing, without a 5xx")
    void twoWritesWantTheSameImage() {
        Stage st = ImageSteps.stage(fixtures);
        Issue other = fixtures.fileIssue(st.customer(), st.code());
        Session secondBrowser = fixtures.signInAgain(st.customer());
        byte[] content = png();
        String id = uploaded(st.customer(), content);
        String table = rows.byId(id).table();
        String firstText = Unique.text("First of two");
        String secondText = Unique.text("Second of two");
        AppLog.Mark mark = log.mark();
        ApiResponse first;
        ApiResponse second;

        try (HeldWrite hold = new HeldWrite(db, postgres, table, "update", id)) {
            CompletableFuture<ApiResponse> a = CompletableFuture.supplyAsync(
                    () -> comment(st.customer(), st.key(), firstText, id));
            hold.awaitHeld("the UPDATE that attaches the image");
            CompletableFuture<ApiResponse> b = CompletableFuture.supplyAsync(
                    () -> comment(secondBrowser, other.key(), secondText, id));
            awaitTheOtherSide(hold, b);
            hold.release();
            first = Concurrently.finish(a, "the first comment");
            second = Concurrently.finish(b, "the second comment");
        }

        assertThat(List.of(first.status(), second.status())).as("statuses: %s / %s", first.snippet(),
                second.snippet()).containsExactlyInAnyOrder(201, 400);
        ApiResponse lost = first.status() == 400 ? first : second;
        assertThat(lost.errorCode()).as("code of the comment that lost").isEqualTo("TEMPORARY_IMAGE_INVALID");
        boolean firstWon = first.status() == 201;
        String wonKey = firstWon ? st.key() : other.key();
        String lostKey = firstWon ? other.key() : st.key();
        assertThat(inlineIds(st.customer(), wonKey)).as("images of the issue whose comment was stored")
                .containsExactly(id);
        assertThat(inlineIds(st.customer(), lostKey)).as("images of the other issue").isEmpty();
        assertThat(download(st.customer(), wonKey, id).body()).as("the attached image").isEqualTo(content);
        assertThat(hasComment(st.customer(), st.key(), firstText)).as("the first comment is stored")
                .isEqualTo(firstWon);
        assertThat(hasComment(st.customer(), other.key(), secondText)).as("the second comment is stored")
                .isEqualTo(!firstWon);
        assertThat(log.since(mark)).as("app log").doesNotContain("40P01");
    }

    @Test
    @DisplayName("S5-AC-32 a comment that attaches an image a second before it expires, while the cleanup comes for "
            + "it: the image is attached and can be read, or the comment is refused and the image is gone, never both")
    void cleanupAndAWriteWantTheSameImage() {
        Stage st = ImageSteps.stage(fixtures);
        byte[] content = png();
        String id = uploaded(st.customer(), content);
        String table = rows.byId(id).table();
        clock.advance(ImageSteps.LIFETIME.minus(Duration.ofSeconds(1)));
        Session customer = fixtures.signInAgain(st.customer());
        String text = Unique.text("Just in time");
        Instant due;
        ApiResponse written;

        try (HeldWrite hold = new HeldWrite(db, postgres, table, "update", id)) {
            CompletableFuture<ApiResponse> write = CompletableFuture.supplyAsync(
                    () -> comment(customer, st.key(), text, id));
            hold.awaitHeld("the UPDATE that attaches the image");
            // Now the image is older than 24 hours and the cleanup (every 10 minutes) is due.
            due = clock.advance(Duration.ofMinutes(11)).truncatedTo(ChronoUnit.MICROS);
            awaitTheOtherSide(hold, null);
            hold.release();
            written = Concurrently.finish(write, "the comment");
        }
        ImageSteps.awaitCleanup(fixtures.signInAdmin(), due);

        assertThat(written.status()).as("the comment: %s", written.snippet()).isIn(201, 400);
        Optional<Row> row = rows.findById(id);
        if (written.status() == 201) {
            assertThat(inlineIds(customer, st.key())).as("images of the issue").containsExactly(id);
            ApiResponse read = download(customer, st.key(), id);
            assertThat(read.status()).as("the attached image after the cleanup ran").isEqualTo(200);
            assertThat(read.body()).isEqualTo(content);
            assertThat(row.map(Row::state)).as("file state of the attached image").contains("stored");
        } else {
            assertThat(written.errorCode()).isEqualTo("TEMPORARY_IMAGE_INVALID");
            assertThat(hasComment(customer, st.key(), text)).as("the refused comment is stored").isFalse();
            assertThat(inlineIds(customer, st.key())).as("images of the issue").isEmpty();
            assertThat(row).as("the row of the deleted image").isEmpty();
            assertThat(rows.contentCopies(content)).as("content of the deleted image").isZero();
        }
    }
}
