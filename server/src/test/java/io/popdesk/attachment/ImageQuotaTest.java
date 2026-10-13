package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MB;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.ImageSteps.image;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.attachment.ImageSteps.Format;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Temporary images and the team quota ({@code POPDESK_ATTACHMENT_QUOTA}, here 2 MB). The quota counts
 * every stored file of the team, so this class has a context and a database of its own, and one test
 * that uses the whole quota. The quota itself and its 80% warning are in {@link AttachmentQuotaTest}.
 */
@TestPropertySource(properties = "POPDESK_ATTACHMENT_QUOTA=2MB")
class ImageQuotaTest extends BareAppTest {

    private static final int QUOTA = 2 * MB;

    @Test
    @DisplayName("S5-AC-06 a temporary image counts against the team quota: with one that fills the quota, the next "
            + "image and the next attachment are both 400 ATTACHMENT_QUOTA_EXCEEDED")
    void temporaryImagesCountAgainstTheQuota() {
        Scene s = new AttachmentSteps(fixtures).scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        AttachmentRows rows = new AttachmentRows(db);
        byte[] nextImage = png();

        ApiResponse toQuota = upload(s.customer(), "fills.png", image(Format.PNG, QUOTA, 1));
        ApiResponse image = upload(s.customer(), "over.png", nextImage);
        ApiResponse attachment = AttachmentSteps.upload(s.customer(), issue.key(), "over.bin", bytes(1, 2));

        assertThat(toQuota.status()).as("an image up to the quota: %s", toQuota.snippet()).isEqualTo(201);
        assertThat(image.status()).as("an image over the quota: %s", image.snippet()).isEqualTo(400);
        assertThat(image.errorCode()).isEqualTo("ATTACHMENT_QUOTA_EXCEEDED");
        assertThat(rows.contentCopies(nextImage)).as("stored copies of the refused image").isZero();
        assertThat(attachment.status()).as("an attachment over the quota: %s", attachment.snippet()).isEqualTo(400);
        assertThat(attachment.errorCode()).isEqualTo("ATTACHMENT_QUOTA_EXCEEDED");
        assertThat(AttachmentSteps.list(s.customer(), issue.key())).as("attachments of the issue").isEmpty();
    }
}
