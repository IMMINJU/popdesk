package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MAX_FILE;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * At most 16 uploads and, apart from them, 16 downloads run at once; the next one is 503
 * {@code SERVER_BUSY} with {@code Retry-After}.
 *
 * <p>The tests hold 16 requests open on raw sockets ({@link HeldRequest}): uploads whose body is
 * only partly sent, downloads of a 10 MB file whose response is not read. Then they send one more
 * until it is refused. An upload that gets through while the 16 are still being taken in is
 * simply stored, so the probes run as an account of their own, under the per-account limit.
 */
class AttachmentConcurrencyLimitTest extends BareAppTest {

    private static final int RUNNING = 16;

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-99 with 16 uploads still sending their body, another upload is 503 SERVER_BUSY with Retry-After; after they finish, uploads work again")
    void seventeenthUploadIsRefused() {
        Scene s = steps.scene();
        Session prober = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Issue probed = fixtures.fileIssue(prober, s.code());
        byte[] content = bytes(200_000, 1);
        List<HeldRequest> held = new ArrayList<>();
        AtomicReference<String> lastProbe = new AtomicReference<>();
        try {
            for (int i = 0; i < RUNNING; i++) {
                held.add(HeldRequest.upload(s.customer().client(),
                        s.customer().api("/issues/" + issue.key() + "/attachments"), "held-" + i + ".bin", content,
                        1_000));
            }

            ApiResponse refused = Wait.until("an upload refused while 16 run", () -> {
                String name = "probe-" + System.nanoTime() + ".bin";
                lastProbe.set(name);
                ApiResponse r = upload(prober, probed.key(), name, bytes(10, 2));
                return r.status() == 201 ? null : r;
            });

            assertThat(refused.status()).as(refused.request()).isEqualTo(503);
            assertThat(refused.errorCode()).isEqualTo("SERVER_BUSY");
            assertThat(refused.header("Retry-After")).as("Retry-After").isNotBlank();
            List<Integer> finished = held.stream().map(HeldRequest::finish).toList();
            assertThat(finished).containsOnly(201);
            assertThat(upload(prober, probed.key(), "after.bin", bytes(10, 3)).status()).isEqualTo(201);
            assertThat(list(prober, probed.key())).extracting(n -> text(n, "fileName"))
                    .contains("after.bin")
                    .doesNotContain(lastProbe.get());
            assertThat(list(s.customer(), issue.key())).hasSize(RUNNING);
        } finally {
            held.forEach(HeldRequest::close);
        }
    }

    @Test
    @DisplayName("S0-AC-99 with 16 downloads still being sent, another download is 503 SERVER_BUSY with Retry-After; after they end, downloads work again")
    void seventeenthDownloadIsRefused() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(MAX_FILE, 4);
        String id = upload(s.customer(), issue.key(), "large.bin", content).expectStatus(201).jsonText("id");
        Session reader = fixtures.signInAgain(s.customer());
        List<HeldRequest> held = new ArrayList<>();
        try {
            for (int i = 0; i < RUNNING; i++) {
                held.add(HeldRequest.download(s.customer().client(), path(s.customer(), issue.key(), id)));
            }

            ApiResponse refused = Wait.until("a download refused while 16 run", () -> {
                ApiResponse r = download(reader, issue.key(), id);
                return r.status() == 200 ? null : r;
            });

            assertThat(refused.status()).as(refused.request()).isEqualTo(503);
            assertThat(refused.errorCode()).isEqualTo("SERVER_BUSY");
            assertThat(refused.header("Retry-After")).as("Retry-After").isNotBlank();
        } finally {
            held.forEach(HeldRequest::close);
        }
        Wait.untilAsserted("downloads work again after the 16 end",
                () -> assertThat(download(reader, issue.key(), id).body()).isEqualTo(content));
    }
}
