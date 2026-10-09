package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.path;
import static io.popdesk.attachment.AttachmentSteps.slice;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Downloads under the issue path, by everyone who can see the issue: always as an attachment with
 * {@code nosniff}, the same bytes, and ranges. The chunked reads are in
 * {@link AttachmentStatementLogTest}.
 */
class AttachmentDownloadTest extends BareAppTest {

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-59 the reporter downloads the same bytes as an attachment with nosniff")
    void reporterDownloads() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(1_500_000, Unique.number());
        String id = upload(s.customer(), issue.key(), "report.bin", content).expectStatus(201).jsonText("id");

        ApiResponse downloaded = download(s.customer(), issue.key(), id);

        assertDownload(downloaded, content);
    }

    @Test
    @DisplayName("S0-AC-59 an assigned agent and a colleague of the company download a public issue's attachment the same way")
    void othersWhoSeeTheIssueDownload() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(300_000, Unique.number());
        String id = upload(s.customer(), issue.key(), "shared.bin", content).expectStatus(201).jsonText("id");

        ApiResponse byAgent = download(agent, issue.key(), id);
        ApiResponse byColleague = download(colleague, issue.key(), id);
        ApiResponse byAdmin = download(s.admin(), issue.key(), id);

        assertDownload(byAgent, content);
        assertDownload(byColleague, content);
        assertDownload(byAdmin, content);
    }

    @Test
    @DisplayName("S0-AC-59 an HTML file is still sent as an attachment with nosniff, never inline")
    void htmlIsAnAttachment() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = AttachmentSteps.text("<html><script>alert(1)</script></html>");
        String id = s.customer().client().post(s.customer().api("/issues/" + issue.key() + "/attachments"))
                .file("file", "page.html", "text/html", content)
                .expect(201)
                .jsonText("id");

        ApiResponse downloaded = download(s.customer(), issue.key(), id);

        assertDownload(downloaded, content);
    }

    @Test
    @DisplayName("S0-AC-59 a range request answers 206 with that part of the file and its Content-Range")
    void rangeRequest() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(2_200_000, Unique.number());
        String id = upload(s.customer(), issue.key(), "range.bin", content).expectStatus(201).jsonText("id");
        int from = 1_048_000;
        int to = 1_049_999;

        ApiResponse part = s.customer().client().get(path(s.customer(), issue.key(), id))
                .header("Range", "bytes=" + from + "-" + to)
                .send();

        assertThat(part.status()).isEqualTo(206);
        assertThat(part.header("Content-Range")).isEqualTo("bytes " + from + "-" + to + "/" + content.length);
        assertThat(part.header("Content-Disposition")).startsWith("attachment");
        assertThat(part.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(part.body()).isEqualTo(slice(content, from, to));
    }

    private static void assertDownload(ApiResponse downloaded, byte[] content) {
        assertThat(downloaded.status()).as(downloaded.request()).isEqualTo(200);
        assertThat(downloaded.header("Content-Disposition")).as("Content-Disposition").startsWith("attachment");
        assertThat(downloaded.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(downloaded.sha256()).isEqualTo(sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
