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
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>A held download has read its response head, so the test knows that each of the 16 began as a
 * 200 of the whole file. The download probes ask for one byte ({@code Range: bytes=0-0}): a range
 * request takes a place like any download, and the probe does not fetch 10 MB each time.
 */
class AttachmentConcurrencyLimitTest extends BareAppTest {

    private static final int RUNNING = 16;

    /** How many log lines, and how much of each, a timed-out wait puts in its message. */
    private static final int LOG_LINES = 40;

    private static final int LOG_LINE_CHARS = 600;

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-99 with 16 uploads still sending their body, another upload is 503 SERVER_BUSY with Retry-After while a download still works; after they finish, uploads work again")
    void seventeenthUploadIsRefused() {
        Scene s = steps.scene();
        Session prober = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Issue probed = fixtures.fileIssue(prober, s.code());
        byte[] readable = bytes(1_000, 5);
        String readableId = upload(prober, probed.key(), "readable.bin", readable).expectStatus(201).jsonText("id");
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
            ApiResponse downloadMeanwhile = download(prober, probed.key(), readableId);
            assertThat(downloadMeanwhile.status()).as("a download while 16 uploads run").isEqualTo(200);
            assertThat(downloadMeanwhile.body()).isEqualTo(readable);
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
    @DisplayName("S0-AC-99 with 16 downloads still being sent, another download is 503 SERVER_BUSY with Retry-After while an upload still works; after they end, downloads work again")
    void seventeenthDownloadIsRefused() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(MAX_FILE, 4);
        String id = upload(s.customer(), issue.key(), "large.bin", content).expectStatus(201).jsonText("id");
        Session reader = fixtures.signInAgain(s.customer());
        AppLog.Mark start = log.mark();
        List<HeldRequest> held = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();
        AtomicReference<ApiResponse> lastProbe = new AtomicReference<>();
        try {
            for (int i = 0; i < RUNNING; i++) {
                held.add(HeldRequest.download(s.customer().client(), path(s.customer(), issue.key(), id)));
            }
            List<String> notHeld = new ArrayList<>();
            for (int i = 0; i < held.size(); i++) {
                HeldRequest h = held.get(i);
                if (h.status() != 200 || h.contentLength() != content.length) {
                    notHeld.add("held download " + (i + 1) + " of " + RUNNING + ": " + h.describe());
                }
            }
            assertThat(notHeld)
                    .as("held downloads that did not begin as 200 with Content-Length %d", content.length)
                    .isEmpty();

            ApiResponse refused;
            try {
                refused = Wait.until("a download refused while 16 run", () -> {
                    ApiResponse r = reader.client().get(path(reader, issue.key(), id))
                            .header("Range", "bytes=0-0")
                            .send();
                    probes.incrementAndGet();
                    lastProbe.set(r);
                    return r.status() == 206 ? null : r;
                });
            } catch (AssertionError timedOut) {
                throw new AssertionError(timedOut.getMessage() + System.lineSeparator()
                        + diagnosis(held, probes.get(), lastProbe.get(), start), timedOut);
            }

            assertThat(refused.status()).as(refused.request()).isEqualTo(503);
            assertThat(refused.errorCode()).isEqualTo("SERVER_BUSY");
            assertThat(refused.header("Retry-After")).as("Retry-After").isNotBlank();
            ApiResponse uploadMeanwhile = upload(reader, issue.key(), "meanwhile.bin", bytes(10, 6));
            assertThat(uploadMeanwhile.status()).as("an upload while 16 downloads run").isEqualTo(201);
        } finally {
            held.forEach(HeldRequest::close);
        }
        Wait.untilAsserted("downloads work again after the 16 end",
                () -> assertThat(download(reader, issue.key(), id).body()).isEqualTo(content));
    }

    /**
     * What a timed-out wait for the refusal adds to its message: the probes, each held download (its
     * response head, then whether the app still sends on it), and the app's WARN and ERROR lines
     * since the test began. Reading the held sockets ends their hold, so this runs only on failure.
     */
    private String diagnosis(List<HeldRequest> held, int probes, ApiResponse lastProbe, AppLog.Mark start) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append("Probes sent: ").append(probes).append(", last status: ")
                .append(lastProbe == null ? "none" : Integer.toString(lastProbe.status())).append(nl);
        for (int i = 0; i < held.size(); i++) {
            HeldRequest h = held.get(i);
            out.append("Held download ").append(i + 1).append(" of ").append(held.size()).append(": ")
                    .append(h.describe()).append("; ").append(h.probeAfterTimeout()).append(nl);
        }
        List<String> lines = log.lines(start).stream()
                .filter(line -> line.contains("WARN") || line.contains("ERROR"))
                .toList();
        out.append("App log WARN and ERROR lines since the test began: ").append(lines.size());
        lines.stream().limit(LOG_LINES).forEach(line -> out.append(nl).append("  ")
                .append(line.length() > LOG_LINE_CHARS ? line.substring(0, LOG_LINE_CHARS) + "..." : line));
        if (lines.size() > LOG_LINES) {
            out.append(nl).append("  (").append(lines.size() - LOG_LINES).append(" more)");
        }
        return out.toString();
    }
}
