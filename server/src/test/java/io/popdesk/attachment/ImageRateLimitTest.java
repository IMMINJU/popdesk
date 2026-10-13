package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.ImageSteps.png;
import static io.popdesk.attachment.ImageSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Image uploads are counted with attachment uploads: in the 30 uploads an account has per 60 seconds
 * and in the 16 uploads that run at once. The limits themselves are in {@link AttachmentRateLimitTest}
 * and {@link AttachmentConcurrencyLimitTest}; here only that an image upload is one of them.
 */
class ImageRateLimitTest extends BareAppTest {

    /**
     * How many probes may be stored before the test gives up: with the image uploaded before and the
     * one after, the prober stays under the 20 temporary images of an account ({@link ImageSteps#MAX_IMAGES}).
     */
    private static final int MAX_STORED_PROBES = ImageSteps.MAX_IMAGES - 5;

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S5-AC-07 an image upload is one of the account's 30 uploads in 60 seconds: after 29 attachments and "
            + "one image, the next image and the next attachment are both 429 RATE_LIMITED")
    void imageUploadsShareTheUploadLimit() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        for (int i = 0; i < 29; i++) {
            AttachmentSteps.upload(s.customer(), issue.key(), "file-" + i + ".txt", bytes(10, i)).expectStatus(201);
        }
        byte[] limited = png();

        ApiResponse thirtieth = upload(s.customer(), "thirtieth.png", png());
        ApiResponse image = upload(s.customer(), "limited.png", limited);
        ApiResponse attachment = AttachmentSteps.upload(s.customer(), issue.key(), "limited.txt", bytes(10, 30));

        assertThat(thirtieth.status()).as("the 30th upload, an image: %s", thirtieth.snippet()).isEqualTo(201);
        for (ApiResponse refused : List.of(image, attachment)) {
            assertThat(refused.status()).as("%s: %s", refused.request(), refused.snippet()).isEqualTo(429);
            assertThat(refused.errorCode()).isEqualTo("RATE_LIMITED");
            assertThat(refused.header("Retry-After")).as("Retry-After").isNotBlank();
        }
        assertThat(new AttachmentRows(db).contentCopies(limited)).as("stored copies of the limited image").isZero();
    }

    @Test
    @DisplayName("S5-AC-07 an image upload is one of the 16 uploads that run at once: while 16 attachment uploads "
            + "send their body, an image upload is 503 SERVER_BUSY with Retry-After, and works after they finish")
    void imageUploadsShareTheSixteen() {
        Scene s = steps.scene();
        Session prober = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        // An image upload works before the 16 are held, so a refusal below is for being the 17th.
        upload(prober, "before.png", png()).expectStatus(201);
        byte[] content = bytes(200_000, 1);
        List<HeldRequest> held = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                held.add(HeldRequest.upload(s.customer().client(),
                        s.customer().api("/issues/" + issue.key() + "/attachments"), "held-" + i + ".bin", content,
                        1_000));
            }

            // Each probe that is stored is one more temporary image of the prober. The probing ends well
            // before the 20 of an account, so a 400 for that limit is never read as the answer here.
            int[] storedProbes = {0};
            ApiResponse refused = Wait.until("an image upload refused while 16 uploads run", () -> {
                ApiResponse r = upload(prober, "probe.png", png());
                if (r.status() != 201) {
                    return r;
                }
                return ++storedProbes[0] >= MAX_STORED_PROBES ? r : null;
            });

            assertThat(refused.status()).as("the 16 uploads did not fill up: %s image uploads in a row were stored "
                    + "while 16 attachment uploads were held", storedProbes[0]).isNotEqualTo(201);
            assertThat(refused.status()).as("%s: %s", refused.request(), refused.snippet()).isEqualTo(503);
            assertThat(refused.errorCode()).isEqualTo("SERVER_BUSY");
            assertThat(refused.header("Retry-After")).as("Retry-After").isNotBlank();
            assertThat(held.stream().map(HeldRequest::finish).toList()).as("the 16 held uploads").containsOnly(201);
            assertThat(upload(prober, "after.png", png()).status()).as("an image upload after the 16 finished")
                    .isEqualTo(201);
        } finally {
            held.forEach(HeldRequest::close);
        }
    }
}
