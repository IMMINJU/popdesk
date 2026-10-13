package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The cleanup of temporary images ({@code temporary-image-cleanup}, every 10 minutes of the app
 * clock): it deletes the file and the row of every temporary image older than 24 hours, leaves
 * attached and newer images alone, and shows its last run in the system status like the other jobs.
 *
 * <p>The attachment table is found from a row the test made ({@link AttachmentRows}); no table or
 * column of this slice is named here.
 */
class ImageCleanupTest extends BareAppTest {

    @Test
    @DisplayName("S5-AC-31, S5-AC-33 after 24 hours the cleanup deletes the file and the row of expired temporary "
            + "images without a trace, keeps an attached image and one uploaded since, and the system status shows "
            + "its run and how many it deleted")
    void expiredImagesAreDeleted() {
        Stage st = ImageSteps.stage(fixtures);
        AttachmentRows rows = new AttachmentRows(db);
        byte[] firstContent = png();
        byte[] secondContent = png();
        byte[] attachedContent = png();
        String first = uploaded(st.customer(), firstContent);
        String second = uploaded(st.agent(), secondContent);
        String attached = ImageSteps.attachedByComment(st.customer(), st.key(), attachedContent);
        assertThat(rows.byId(first).state()).as("file state of a temporary image before it expires")
                .isEqualTo("stored");
        assertThat(rows.contentCopies(firstContent)).as("stored copies before it expires").isEqualTo(1);

        Instant due = clock.advance(ImageSteps.LIFETIME.plusSeconds(1)).truncatedTo(ChronoUnit.MICROS);
        Session customer = fixtures.signInAgain(st.customer());
        byte[] newContent = png();
        String uploadedSince = uploaded(customer, newContent);
        JsonNode job = ImageSteps.awaitCleanup(fixtures.signInAdmin(), due);

        assertThat(rows.findById(first)).as("the row of an expired image").isEmpty();
        assertThat(rows.findById(second)).as("the row of an expired image of an agent").isEmpty();
        assertThat(rows.contentCopies(firstContent) + rows.contentCopies(secondContent))
                .as("stored copies of the expired images").isZero();
        assertThat(TalkFlow.tablesContaining(db, first)).as("columns that still hold the ID of a deleted image")
                .isEmpty();
        assertThat(rows.findById(attached).map(Row::state)).as("file state of the attached image").contains("stored");
        assertThat(download(customer, st.key(), attached).body()).as("the attached image").isEqualTo(attachedContent);
        assertThat(read(customer, uploadedSince).body()).as("the image uploaded since").isEqualTo(newContent);
        assertThat(job.path("deleted").isIntegralNumber() ? job.path("deleted").asLong() : -1)
                .as("deleted in %s", job).isGreaterThanOrEqualTo(2);
        assertThat(OffsetDateTime.parse(job.path("lastRunAt").stringValue()).toInstant())
                .as("lastRunAt in %s", job).isAfterOrEqualTo(due);
    }

    @Test
    @DisplayName("S5-AC-34 while the file of an expired image cannot be removed its row and file stay and nobody "
            + "reads it; once the file can be removed, a later run deletes both")
    void fileThatCannotBeRemovedIsTriedAgain() {
        Stage st = ImageSteps.stage(fixtures);
        AttachmentRows rows = new AttachmentRows(db);
        byte[] content = png();
        String id = uploaded(st.customer(), content);
        Session customer;
        try (FailureTriggers triggers = new FailureTriggers(db)) {
            triggers.failRemovingContent(content);
            AppLog.Mark mark = log.mark();
            Instant due = clock.advance(ImageSteps.LIFETIME.plusSeconds(1)).truncatedTo(ChronoUnit.MICROS);
            customer = fixtures.signInAgain(st.customer());
            Session admin = fixtures.signInAdmin();
            // The job runs within a second of being due: it leaves its record, or the scheduler logs that it failed.
            Wait.until("a run of " + ImageSteps.CLEANUP_JOB + " at or after " + due + ", recorded or logged as failed",
                    Duration.ofSeconds(60), () -> ImageSteps.cleanupRun(admin, due) != null
                            || log.since(mark).contains("'" + ImageSteps.CLEANUP_JOB + "' failed") ? Boolean.TRUE : null);

            assertThat(rows.findById(id)).as("the row of the image whose file cannot be removed").isPresent();
            assertThat(rows.contentCopies(content)).as("stored copies of that file").isEqualTo(1);
            ImageSteps.assertReadsLikeUnknown(customer, id, "an expired image whose file is still there");
        }

        clock.advance(Duration.ofMinutes(11));

        Wait.untilAsserted("the row and the file are deleted by a later run", Duration.ofSeconds(60), () -> {
            assertThat(rows.findById(id)).as("the row of the image").isEmpty();
            assertThat(rows.contentCopies(content)).as("stored copies of its file").isZero();
        });
    }

    @Test
    @DisplayName("S5-AC-12, S5-AC-31 an image whose upload stopped halfway (pending) is read by nobody, and its row "
            + "is gone after the cleanups of the next day (acceptance.md 5절 17)")
    void imageLeftPendingIsDeleted() {
        Stage st = ImageSteps.stage(fixtures);
        AttachmentRows rows = new AttachmentRows(db);
        String table = rows.byId(uploaded(st.customer())).table();
        String name = "pending-" + Unique.number() + ".png";
        byte[] content = png();
        ApiResponse failed;
        try (FailureTriggers triggers = new FailureTriggers(db)) {
            triggers.failStoring(table, name);
            failed = ImageSteps.upload(st.customer(), name, content);
        }
        assertThat(failed.status()).as("an upload whose last step fails: %s", failed.snippet()).isEqualTo(500);
        assertThat(rows.byFileName(name)).as("rows named %s", name).extracting(Row::state).containsExactly("pending");
        String id = rows.byFileName(name).getFirst().id();
        ImageSteps.assertReadsLikeUnknown(st.customer(), id, "an image that is still pending");

        Instant nextDay = clock.advance(ImageSteps.LIFETIME.plusSeconds(1)).truncatedTo(ChronoUnit.MICROS);
        Session admin = fixtures.signInAdmin();
        ImageSteps.awaitCleanup(admin, nextDay);
        // The attachment cleanup (once a day) ends the pending row in the same round; the next run of the
        // image cleanup, ten minutes later, deletes what is left of it.
        ImageSteps.awaitCleanup(admin, clock.advance(Duration.ofMinutes(11)).truncatedTo(ChronoUnit.MICROS));

        Wait.untilAsserted("the row and the file of the pending image are deleted", Duration.ofSeconds(60), () -> {
            assertThat(rows.findById(id)).as("the row of the pending image").isEmpty();
            assertThat(rows.contentCopies(content)).as("stored copies of its file").isZero();
        });
    }
}
