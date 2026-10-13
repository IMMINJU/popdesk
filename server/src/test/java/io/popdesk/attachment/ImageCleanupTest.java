package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static io.popdesk.issue.TalkFlow.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.ImageSteps.Stage;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
            + "images without a trace, keeps an attached image and images uploaded an hour later (23 hours old, "
            + "still read and attached), and the system status shows its run and how many it deleted")
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

        // The young images are there before the clock reaches the run that deletes the old ones, so a
        // cleanup that takes every temporary image, whatever its age, takes these too.
        clock.advance(Duration.ofHours(1));
        byte[] youngContent = png();
        String young = uploaded(st.customer(), youngContent);
        String youngToAttach = uploaded(st.customer());

        Instant due = clock.advance(ImageSteps.LIFETIME.minusHours(1).plusSeconds(1)).truncatedTo(ChronoUnit.MICROS);
        Session customer = fixtures.signInAgain(st.customer());
        JsonNode job = ImageSteps.awaitCleanup(fixtures.signInAdmin(), due);

        assertThat(rows.findById(first)).as("the row of an expired image").isEmpty();
        assertThat(rows.findById(second)).as("the row of an expired image of an agent").isEmpty();
        assertThat(rows.contentCopies(firstContent) + rows.contentCopies(secondContent))
                .as("stored copies of the expired images").isZero();
        assertThat(TalkFlow.tablesContaining(db, first)).as("columns that still hold the ID of a deleted image")
                .isEmpty();
        assertThat(rows.findById(attached).map(Row::state)).as("file state of the attached image").contains("stored");
        assertThat(download(customer, st.key(), attached).body()).as("the attached image").isEqualTo(attachedContent);
        ApiResponse youngRead = read(customer, young);
        assertThat(youngRead.status()).as("the image of 23 hours after the cleanup ran: %s", youngRead.snippet())
                .isEqualTo(200);
        assertThat(youngRead.body()).as("the image of 23 hours").isEqualTo(youngContent);
        assertThat(rows.findById(youngToAttach).map(Row::state)).as("file state of the other image of 23 hours")
                .contains("stored");
        ImageSteps.commentWith(customer, st.key(), Unique.text("Attached at 23 hours"), List.of(youngToAttach));
        assertThat(ImageSteps.inlineIds(customer, st.key())).as("inline attachments of the issue")
                .containsExactlyInAnyOrder(attached, youngToAttach);
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
        try (FailedRemoval removal = new FailedRemoval(db, content)) {
            clock.advance(ImageSteps.LIFETIME.plusSeconds(1));
            customer = fixtures.signInAgain(st.customer());
            Wait.until("the cleanup tried to remove the file of the expired image", Duration.ofSeconds(60),
                    () -> removal.tries() > 0 ? Boolean.TRUE : null);

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

    /**
     * Fails every removal (UPDATE or DELETE) of the row of {@code attachment_content} that holds one
     * file, with a {@code test_} trigger, and counts the tries in a {@code test_} sequence. A sequence
     * keeps its value when the transaction that moved it is rolled back, so the count tells that a run
     * of the cleanup reached the file, whatever the run records or logs.
     */
    private static final class FailedRemoval implements AutoCloseable {

        private static final String FUNCTION = "test_popdesk_image_removal_counted";

        private final DbInspector db;
        private final String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        private final String sequence;

        FailedRemoval(DbInspector db, byte[] content) {
            this.db = db;
            sequence = AttachmentRows.quote(db.schema()) + "." + name;
            db.schemaFixture("create sequence " + sequence);
            db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                    + "begin\n"
                    + "  if exists (select 1 from jsonb_each_text(to_jsonb(OLD)) e where e.value = TG_ARGV[0]) then\n"
                    + "    perform nextval(TG_ARGV[1]::regclass);\n"
                    + "    raise exception 'test_ trigger: injected failure';\n"
                    + "  end if;\n"
                    + "  if TG_OP = 'DELETE' then return OLD; end if;\n"
                    + "  return NEW;\n"
                    + "end $f$");
            // In to_jsonb a bytea value reads as text: a backslash, x, and lower-case hex.
            db.schemaFixture("create trigger " + name + " before update or delete on "
                    + AttachmentRows.quote(AttachmentRows.CONTENT_TABLE) + " for each row execute function "
                    + FUNCTION + "(E'\\\\x" + HexFormat.of().formatHex(content) + "', '" + sequence + "')");
        }

        /** How many times a removal of the file was tried and failed. */
        long tries() {
            return db.count("select case when is_called then last_value else 0 end from " + sequence);
        }

        @Override
        public void close() {
            db.schemaFixture("drop trigger if exists " + name + " on "
                    + AttachmentRows.quote(AttachmentRows.CONTENT_TABLE));
            db.schemaFixture("drop sequence if exists " + sequence);
        }
    }

    @Test
    @DisplayName("S5-AC-12, S5-AC-21, S5-AC-31 an image whose upload stopped halfway (pending) is read by nobody, a "
            + "comment that names it is refused like one that names an unknown ID, and its row is gone after the "
            + "cleanups of the next day (acceptance.md 5절 17)")
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
        String refusedContent = Unique.text("With a pending image");
        ApiResponse unknown = TalkFlow.addRequest(st.customer(), st.key(), body("content", refusedContent, "imageIds",
                List.of(ImageSteps.unknownId()))).send();
        ApiResponse refused = TalkFlow.addRequest(st.customer(), st.key(), body("content", refusedContent, "imageIds",
                List.of(id))).send();
        assertThat(unknown.status()).as("a comment with an unknown ID: %s", unknown.snippet()).isEqualTo(400);
        assertThat(unknown.errorCode()).isEqualTo("TEMPORARY_IMAGE_INVALID");
        assertThat(refused.status()).as("a comment with the pending image: %s", refused.snippet())
                .isEqualTo(unknown.status());
        assertThat(refused.text()).as("body for the pending image, like for an unknown ID").isEqualTo(unknown.text());
        assertThat(TalkFlow.comments(st.customer(), st.key())).as("comments of the issue")
                .noneMatch(comment -> refusedContent.equals(AttachmentSteps.text(comment, "content")));
        assertThat(rows.byFileName(name)).as("rows named %s after the refused comment", name).extracting(Row::state)
                .containsExactly("pending");

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
