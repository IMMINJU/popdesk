package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MAX_FILE;
import static io.popdesk.attachment.AttachmentSteps.MAX_PUBLIC_PER_ISSUE;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.fieldCode;
import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static io.popdesk.attachment.AttachmentSteps.uploadToComment;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Limits of one file (1 byte to 10 MB, a name of 1 to 255 characters) and of the public attachments
 * of one issue (50 MB, internal attachments not counted). The team quota is in
 * {@link AttachmentQuotaTest}.
 */
class AttachmentLimitsTest extends BareAppTest {

    /** 10 MB of content, shared by the tests that fill an issue. */
    private static final byte[] TEN_MB = bytes(MAX_FILE, 10);

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    // One file

    @Test
    @DisplayName("S0-AC-57 a file of 0 bytes is 400 FILE_EMPTY and is not stored")
    void emptyFile() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse rejected = upload(s.customer(), issue.key(), "empty.txt", new byte[0]);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("FILE_EMPTY");
        assertThat(list(s.customer(), issue.key())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-57 a file of exactly 10 MB is stored")
    void tenMegabytes() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse created = upload(s.customer(), issue.key(), "ten.bin", TEN_MB);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("size").asLong()).isEqualTo(MAX_FILE);
        assertThat(list(s.customer(), issue.key())).hasSize(1);
    }

    @Test
    @DisplayName("S0-AC-57 a file of 10 MB and one byte is 400 FILE_TOO_LARGE and is not stored")
    void overTenMegabytes() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse rejected = upload(s.customer(), issue.key(), "over.bin", bytes(MAX_FILE + 1, 11));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("FILE_TOO_LARGE");
        assertThat(list(s.customer(), issue.key())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-57 file names of 1 and 255 characters are stored as sent")
    void namesOfOneAnd255Characters() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String one = "a";
        String max = "n".repeat(251) + ".txt";

        ApiResponse shortest = upload(s.customer(), issue.key(), one, bytes(5, 1));
        ApiResponse longest = upload(s.customer(), issue.key(), max, bytes(5, 2));

        assertThat(shortest.status()).isEqualTo(201);
        assertThat(longest.status()).isEqualTo(201);
        assertThat(list(s.customer(), issue.key())).extracting(n -> text(n, "fileName"))
                .containsExactlyInAnyOrder(one, max);
    }

    @Test
    @DisplayName("S0-AC-57 an empty file name is 400 INVALID_INPUT with file REQUIRED")
    void emptyName() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse rejected = upload(s.customer(), issue.key(), "", bytes(5, 3));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "file")).isEqualTo("REQUIRED");
        assertThat(list(s.customer(), issue.key())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-57 a file name of 256 characters is 400 INVALID_INPUT with file TOO_LONG")
    void nameOf256Characters() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse rejected = upload(s.customer(), issue.key(), "n".repeat(252) + ".txt", bytes(5, 4));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "file")).isEqualTo("TOO_LONG");
        assertThat(list(s.customer(), issue.key())).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-57 filing an issue with a file name of 256 characters is 400 with files TOO_LONG and files nothing")
    void filingWithLongName() {
        Scene s = steps.scene();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", Unique.text("Long name"));

        ApiResponse rejected = s.customer().client().post("/api/portal/issues")
                .jsonPart("issue", body)
                .file("files", "n".repeat(252) + ".txt", "text/plain", bytes(5, 5))
                .send();

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "files")).isEqualTo("TOO_LONG");
        assertThat(s.customer().client().get("/api/portal/issues").expect(200).json().path("totalElements").asLong())
                .isZero();
    }

    // Public attachments of one issue

    @Test
    @DisplayName("S0-AC-57 a customer hits the 50 MB public limit at the same byte with or without internal attachments on the issue")
    void publicLimitIgnoresInternalAttachments() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue plain = fillPublic(s.customer(), s.code());
        Issue mixed = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).file(new File("first.bin",
                "application/octet-stream", TEN_MB)));
        Comment internal = fixtures.internalComment(agent, mixed.key(), "Internal logs");
        uploadToComment(agent, mixed.key(), internal.id(), "internal-1.bin", TEN_MB).expectStatus(201);
        uploadToComment(agent, mixed.key(), internal.id(), "internal-2.bin", TEN_MB).expectStatus(201);
        addPublic(s.customer(), mixed.key());

        ApiResponse overPlain = upload(s.customer(), plain.key(), "one-more.bin", bytes(1, 6));
        ApiResponse overMixed = upload(s.customer(), mixed.key(), "one-more.bin", bytes(1, 6));

        assertThat(overPlain.status()).isEqualTo(400);
        assertThat(overPlain.errorCode()).isEqualTo("ISSUE_ATTACHMENTS_TOO_LARGE");
        assertThat(overMixed.status()).isEqualTo(400);
        assertThat(overMixed.errorCode()).isEqualTo("ISSUE_ATTACHMENTS_TOO_LARGE");
        assertThat(publicBytes(s.customer(), plain.key())).isEqualTo(MAX_PUBLIC_PER_ISSUE);
        assertThat(publicBytes(s.customer(), mixed.key())).isEqualTo(MAX_PUBLIC_PER_ISSUE);
    }

    @Test
    @DisplayName("S0-AC-57 on an issue with 50 MB of public attachments, staff can still add an internal attachment")
    void internalAttachmentsDoNotCount() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue full = fillPublic(s.customer(), s.code());
        Comment internal = fixtures.internalComment(agent, full.key(), "Internal note");

        ApiResponse added = uploadToComment(agent, full.key(), internal.id(), "internal.bin", TEN_MB);
        ApiResponse publicByAgent = upload(agent, full.key(), "public.bin", bytes(1, 7));

        assertThat(added.status()).isEqualTo(201);
        assertThat(publicByAgent.status()).isEqualTo(400);
        assertThat(publicByAgent.errorCode()).isEqualTo("ISSUE_ATTACHMENTS_TOO_LARGE");
    }

    /** A new issue with exactly 50 MB of public attachments: one sent with the issue, two on it, two on comments. */
    private Issue fillPublic(Session customer, String code) {
        Issue issue = fixtures.fileIssue(customer, NewIssue.in(code).file(new File("first.bin",
                "application/octet-stream", TEN_MB)));
        addPublic(customer, issue.key());
        return issue;
    }

    /** Adds 40 MB of public attachments by the customer: two on the issue, two on their own comments. */
    private void addPublic(Session customer, String key) {
        upload(customer, key, "second.bin", TEN_MB).expectStatus(201);
        upload(customer, key, "third.bin", TEN_MB).expectStatus(201);
        for (int i = 0; i < 2; i++) {
            Comment comment = fixtures.comment(customer, key, "Logs " + i);
            uploadToComment(customer, key, comment.id(), "comment-" + i + ".bin", TEN_MB).expectStatus(201);
        }
    }

    private static long publicBytes(Session customer, String key) {
        return list(customer, key).stream().mapToLong(n -> n.path("size").asLong()).sum();
    }
}
