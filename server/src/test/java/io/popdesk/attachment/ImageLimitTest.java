package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MAX_FILE;
import static io.popdesk.attachment.ImageSteps.MAX_IMAGES;
import static io.popdesk.attachment.ImageSteps.image;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.read;
import static io.popdesk.attachment.ImageSteps.upload;
import static io.popdesk.attachment.ImageSteps.uploaded;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.attachment.ImageSteps.Format;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What one account may hold as temporary images at a time (ATT-7): 20 images and 50 MB. Attached and
 * expired images are not counted. Sizes are read as in {@link AttachmentSteps}: 50 MB is 50 MiB.
 */
class ImageLimitTest extends BareAppTest {

    private static final String LIMIT = "TEMPORARY_IMAGE_LIMIT_EXCEEDED";

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    private static List<String> uploadTwenty(Session session) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < MAX_IMAGES; i++) {
            ids.add(uploaded(session));
        }
        return ids;
    }

    private static void assertLimited(ApiResponse refused, byte[] content, AttachmentRows rows) {
        assertThat(refused.status()).as("%s: %s", refused.request(), refused.snippet()).isEqualTo(400);
        assertThat(refused.errorCode()).isEqualTo(LIMIT);
        assertThat(rows.contentCopies(content)).as("stored copies of the refused image").isZero();
    }

    @Test
    @DisplayName("S5-AC-05 the 20th temporary image of an account is stored and the 21st is 400 "
            + "TEMPORARY_IMAGE_LIMIT_EXCEEDED; another account is not held back, and attaching one makes room for one")
    void twentyFirstImageIsRefused() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        AttachmentRows rows = new AttachmentRows(db);
        List<String> ids = uploadTwenty(s.customer());
        byte[] over = png();

        ApiResponse twentyFirst = upload(s.customer(), "over.png", over);
        ApiResponse byColleague = upload(colleague, "mine.png", png());

        assertLimited(twentyFirst, over, rows);
        assertThat(byColleague.status()).as("an upload of another account: %s", byColleague.snippet()).isEqualTo(201);
        assertThat(read(s.customer(), ids.getLast()).status()).as("the 20th image, read back").isEqualTo(200);

        ImageSteps.commentWith(s.customer(), issue.key(), "Attaching the first", List.of(ids.getFirst()));
        ApiResponse afterAttaching = upload(s.customer(), "room.png", png());
        byte[] overAgain = png();
        ApiResponse fullAgain = upload(s.customer(), "over-again.png", overAgain);

        assertThat(afterAttaching.status()).as("an upload after one image was attached: %s",
                afterAttaching.snippet()).isEqualTo(201);
        assertLimited(fullAgain, overAgain, rows);
    }

    @Test
    @DisplayName("S5-AC-05, S5-AC-30 temporary images older than 24 hours are not counted: after 20 of them expired "
            + "the account uploads again")
    void expiredImagesAreNotCounted() {
        Scene s = steps.scene();
        uploadTwenty(s.customer());
        clock.advance(ImageSteps.LIFETIME.minus(Duration.ofSeconds(1)));
        Session beforeExpiry = fixtures.signInAgain(s.customer());
        byte[] early = png();

        ApiResponse stillFull = upload(beforeExpiry, "early.png", early);
        clock.advance(Duration.ofSeconds(2));
        ApiResponse afterExpiry = upload(beforeExpiry, "late.png", png());

        assertLimited(stillFull, early, new AttachmentRows(db));
        assertThat(afterExpiry.status()).as("an upload after the 20 expired: %s", afterExpiry.snippet())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("S5-AC-04, S5-AC-05 five images of exactly 10 MB are stored, 50 MB in all; one more small image is 400 "
            + "TEMPORARY_IMAGE_LIMIT_EXCEEDED; attaching one of the five makes room for 10 MB more, and no more")
    void fiftyMegabytes() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        AttachmentRows rows = new AttachmentRows(db);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ApiResponse stored = upload(s.customer(), "ten-" + i + ".png", image(Format.PNG, MAX_FILE,
                    Unique.number()));
            assertThat(stored.status()).as("image %s of 10 MB: %s", i + 1, stored.snippet()).isEqualTo(201);
            ids.add(stored.jsonText("id"));
        }
        byte[] over = png();

        ApiResponse refused = upload(s.customer(), "one-more.png", over);

        assertLimited(refused, over, rows);

        ImageSteps.commentWith(s.customer(), issue.key(), "Attaching the first 10 MB", List.of(ids.getFirst()));
        ApiResponse afterAttaching = upload(s.customer(), "ten-again.png", image(Format.PNG, MAX_FILE,
                Unique.number()));
        byte[] overAgain = png();
        ApiResponse fullAgain = upload(s.customer(), "one-more-again.png", overAgain);

        assertThat(afterAttaching.status()).as("10 MB more after one of the five was attached: %s",
                afterAttaching.snippet()).isEqualTo(201);
        assertLimited(fullAgain, overAgain, rows);
    }
}
