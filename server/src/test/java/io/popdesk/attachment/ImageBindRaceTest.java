package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.ImageSteps.inlineIds;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.issue.IssueFlow;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

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
 *
 * <p>A round in which the other side only had its seconds, without finishing or waiting for a lock,
 * did not meet the held request: it is checked like the others but not counted. Each of the two runs
 * {@link #ROUNDS} rounds and fails when the two sides met in none of them.
 *
 * <p>The last test is the closing of an issue while a comment with an image is on its way, in the
 * form of {@code IssueCloseRaceTest} (S3-AC-52): there the closing request is the one held.
 */
class ImageBindRaceTest extends BareAppTest {

    /** How long the first request is held when the other side neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(4);

    /** How many times each pair is set against each other. */
    private static final int ROUNDS = 5;

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

    /**
     * Waits until the other side finished or waits for a lock, at most {@link #HELD_AT_MOST}. True when
     * it did one of the two while the first request was held; false when it only had its time.
     */
    private static boolean metTheOtherSide(HeldWrite hold, BooleanSupplier finished) {
        long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
        return Wait.until("the other side finished, waits for a lock, or had " + HELD_AT_MOST, () -> {
            if (finished.getAsBoolean() || hold.waitingForALock() > 0) {
                return Boolean.TRUE;
            }
            return System.nanoTime() > heldUntil ? Boolean.FALSE : null;
        });
    }

    @Test
    @DisplayName("S5-AC-22 two comments on two issues that name the same image at the same time: one is stored with "
            + "the image, the other is 400 TEMPORARY_IMAGE_INVALID and stores nothing, without a 5xx; in five "
            + "rounds the two met at least once")
    void twoWritesWantTheSameImage() {
        int met = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            if (twoWritesOnce(round)) {
                met++;
            }
        }

        assertThat(met).as("rounds, of %s, in which the second comment finished or waited for a lock while the "
                + "first was held at its UPDATE: the two never met", ROUNDS).isPositive();
    }

    private boolean twoWritesOnce(int round) {
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
        boolean met;

        try (HeldWrite hold = new HeldWrite(db, postgres, table, "update", id)) {
            CompletableFuture<ApiResponse> a = CompletableFuture.supplyAsync(
                    () -> comment(st.customer(), st.key(), firstText, id));
            hold.awaitHeld("the UPDATE that attaches the image");
            CompletableFuture<ApiResponse> b = CompletableFuture.supplyAsync(
                    () -> comment(secondBrowser, other.key(), secondText, id));
            met = metTheOtherSide(hold, b::isDone);
            hold.release();
            first = Concurrently.finish(a, "the first comment");
            second = Concurrently.finish(b, "the second comment");
        }

        assertThat(List.of(first.status(), second.status())).as("round %s, statuses: %s / %s", round,
                first.snippet(), second.snippet()).containsExactlyInAnyOrder(201, 400);
        ApiResponse lost = first.status() == 400 ? first : second;
        assertThat(lost.errorCode()).as("round %s, code of the comment that lost", round)
                .isEqualTo("TEMPORARY_IMAGE_INVALID");
        boolean firstWon = first.status() == 201;
        String wonKey = firstWon ? st.key() : other.key();
        String lostKey = firstWon ? other.key() : st.key();
        assertThat(inlineIds(st.customer(), wonKey)).as("round %s, images of the issue whose comment was stored",
                round).containsExactly(id);
        assertThat(inlineIds(st.customer(), lostKey)).as("round %s, images of the other issue", round).isEmpty();
        assertThat(download(st.customer(), wonKey, id).body()).as("round %s, the attached image", round)
                .isEqualTo(content);
        assertThat(hasComment(st.customer(), st.key(), firstText)).as("round %s, the first comment is stored", round)
                .isEqualTo(firstWon);
        assertThat(hasComment(st.customer(), other.key(), secondText)).as("round %s, the second comment is stored",
                round).isEqualTo(!firstWon);
        assertThat(log.since(mark)).as("round %s, app log", round).doesNotContain("40P01");
        return met;
    }

    @Test
    @DisplayName("S5-AC-32 a comment that attaches an image a second before it expires, while the cleanup comes for "
            + "it: the image is attached and can be read, or the comment is refused and the image is gone, never "
            + "both; in five rounds the cleanup met the comment at least once")
    void cleanupAndAWriteWantTheSameImage() {
        int met = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            if (cleanupAndAWriteOnce(round)) {
                met++;
            }
        }

        assertThat(met).as("rounds, of %s, in which the cleanup ran or waited for a lock while the comment was "
                + "held at its UPDATE: the two never met", ROUNDS).isPositive();
    }

    private boolean cleanupAndAWriteOnce(int round) {
        Stage st = ImageSteps.stage(fixtures);
        byte[] content = png();
        String id = uploaded(st.customer(), content);
        String table = rows.byId(id).table();
        clock.advance(ImageSteps.LIFETIME.minus(Duration.ofSeconds(1)));
        Session customer = fixtures.signInAgain(st.customer());
        Session admin = fixtures.signInAdmin();
        String text = Unique.text("Just in time");
        Instant due;
        ApiResponse written;
        boolean met;

        try (HeldWrite hold = new HeldWrite(db, postgres, table, "update", id)) {
            CompletableFuture<ApiResponse> write = CompletableFuture.supplyAsync(
                    () -> comment(customer, st.key(), text, id));
            hold.awaitHeld("the UPDATE that attaches the image");
            // Now the image is older than 24 hours and the cleanup (every 10 minutes) is due.
            Instant dueNow = clock.advance(Duration.ofMinutes(11)).truncatedTo(ChronoUnit.MICROS);
            due = dueNow;
            // The cleanup met the comment when it waits for the row, or when its run is recorded already
            // (it went past a row it could not lock).
            met = metTheOtherSide(hold, () -> ImageSteps.cleanupRun(admin, dueNow) != null);
            hold.release();
            written = Concurrently.finish(write, "the comment");
        }
        ImageSteps.awaitCleanup(admin, due);

        assertThat(written.status()).as("round %s, the comment: %s", round, written.snippet()).isIn(201, 400);
        Optional<Row> row = rows.findById(id);
        if (written.status() == 201) {
            assertThat(inlineIds(customer, st.key())).as("round %s, images of the issue", round).containsExactly(id);
            ApiResponse read = download(customer, st.key(), id);
            assertThat(read.status()).as("round %s, the attached image after the cleanup ran", round).isEqualTo(200);
            assertThat(read.body()).isEqualTo(content);
            assertThat(row.map(Row::state)).as("round %s, file state of the attached image", round)
                    .contains("stored");
        } else {
            assertThat(written.errorCode()).as("round %s", round).isEqualTo("TEMPORARY_IMAGE_INVALID");
            assertThat(hasComment(customer, st.key(), text)).as("round %s, the refused comment is stored", round)
                    .isFalse();
            assertThat(inlineIds(customer, st.key())).as("round %s, images of the issue", round).isEmpty();
            assertThat(row).as("round %s, the row of the deleted image", round).isEmpty();
            assertThat(rows.contentCopies(content)).as("round %s, content of the deleted image", round).isZero();
        }
        return met;
    }

    @Test
    @DisplayName("S5-AC-28 a comment with an image that overlaps the closing of its issue: it is stored with the "
            + "image, not later than the closing, or it is 400 ISSUE_CLOSED, stores nothing, and the image stays "
            + "temporary; without a 5xx")
    void closingAndAWriteWithAnImage() {
        Stage st = ImageSteps.stage(fixtures);
        byte[] content = png();
        String id = uploaded(st.customer(), content);
        String title = text(ImageSteps.detail(st.agent(), st.key()), "title");
        String issueTable = rows.tableHolding(title);
        String marker = Unique.text("While closing");
        AppLog.Mark mark = log.mark();
        ApiResponse closing;
        ApiResponse written;

        try (HeldWrite hold = new HeldWrite(db, postgres, issueTable, "update", title)) {
            CompletableFuture<ApiResponse> close = CompletableFuture.supplyAsync(
                    () -> IssueFlow.statusRequest(st.agent(), st.key(), "CLOSED").send());
            hold.awaitHeld("the UPDATE that closes the issue");
            clock.advance(Duration.ofSeconds(1));
            CompletableFuture<ApiResponse> write = CompletableFuture.supplyAsync(
                    () -> comment(st.customer(), st.key(), marker, id));
            metTheOtherSide(hold, write::isDone);
            hold.release();
            closing = Concurrently.finish(close, "the closing request");
            written = Concurrently.finish(write, "the comment");
        }

        assertThat(closing.status()).as("closing: %s", closing.snippet()).isEqualTo(200);
        assertThat(written.status()).as("the comment: %s", written.snippet()).isIn(201, 400);
        JsonNode after = ImageSteps.detail(st.agent(), st.key());
        assertThat(text(after, "status")).as("status after both requests").isEqualTo("CLOSED");
        Instant closedAt = IssueFlow.time(after, "closedAt");
        assertThat(closedAt).as("closedAt after both requests").isNotNull();
        JsonNode stored = TalkFlow.comments(st.agent(), st.key()).stream()
                .filter(c -> marker.equals(text(c, "content"))).findFirst().orElse(null);
        if (written.status() == 201) {
            assertThat(stored).as("the comment under the closed issue").isNotNull();
            assertThat(IssueFlow.time(stored, "createdAt")).as("time of the comment, not after the closing")
                    .isBeforeOrEqualTo(closedAt);
            assertThat(inlineIds(st.customer(), st.key())).as("images of the issue").containsExactly(id);
            assertThat(download(st.customer(), st.key(), id).body()).as("the attached image").isEqualTo(content);
        } else {
            assertThat(written.errorCode()).as("code of the refused comment").isEqualTo("ISSUE_CLOSED");
            assertThat(stored).as("the refused comment under the closed issue").isNull();
            assertThat(inlineIds(st.agent(), st.key())).as("images of the closed issue").isEmpty();
            ApiResponse temporary = read(st.customer(), id);
            assertThat(temporary.status()).as("the image, still temporary: %s", temporary.snippet()).isEqualTo(200);
            assertThat(temporary.body()).isEqualTo(content);
        }
        assertThat(log.since(mark)).as("app log").doesNotContain("40P01");
    }
}
