package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a failed filing leaves behind, and what the daily cleanup leaves alone.
 *
 * <p>Only the parts that HTTP can reach are here. How a test makes the cleanup run, how it puts a
 * failure right before the issue number is taken, and how it races the cleanup against step ③ are
 * open questions in the report.
 */
class AttachmentCleanupTest extends BareAppTest {

    private AttachmentSteps steps;
    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("S0-AC-63 a filing rejected for its title files nothing, and its file is in no list and never stored")
    void filingWithBadTitle() {
        Scene s = steps.scene();
        String name = Unique.text("bad-title") + ".txt";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", "");

        ApiResponse rejected = s.customer().client().post("/api/portal/issues")
                .jsonPart("issue", body)
                .file("files", name, "text/plain", bytes(50, 1))
                .send();

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertNothingFiled(s, name);
    }

    @Test
    @DisplayName("S0-AC-63 a filing rejected for an empty second file files nothing, leaves the first file unseen and never stored, and uses no number")
    void filingWithEmptySecondFile() {
        Scene s = steps.scene();
        String name = Unique.text("first-ok") + ".txt";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", Unique.text("Two files"));

        ApiResponse rejected = s.customer().client().post("/api/portal/issues")
                .jsonPart("issue", body)
                .file("files", name, "text/plain", bytes(50, 2))
                .file("files", "empty.txt", "text/plain", new byte[0])
                .send();

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("FILE_EMPTY");
        assertNothingFiled(s, name);
        Issue next = fixtures.fileIssue(s.customer(), s.code());
        assertThat(next.key()).isEqualTo(s.code() + "-1");
    }

    @Test
    @DisplayName("S0-AC-62 a stored attachment over an hour old is left alone after a day has passed")
    void storedAttachmentSurvivesCleanup() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(1_000, Unique.number());
        String id = upload(s.customer(), issue.key(), "keep.bin", content).expectStatus(201).jsonText("id");

        clock.advance(Duration.ofHours(25));
        Session again = fixtures.signInAgain(s.customer());

        Wait.holds("the stored attachment stays stored", Duration.ofSeconds(5),
                () -> rows.byId(id).state().equals("stored") && rows.contentCopies(content) == 1);
        assertThat(download(again, issue.key(), id).body()).isEqualTo(content);
    }

    /** No issue was filed, nobody lists the file, and any row for it is still {@code pending}. */
    private void assertNothingFiled(Scene s, String fileName) {
        assertThat(s.customer().client().get("/api/portal/issues").expect(200).json().path("totalElements").asLong())
                .as("issues of the customer").isZero();
        assertThat(s.admin().client().get("/api/console/issues").query("size", 100).expect(200).text())
                .as("console issue list").doesNotContain(s.code() + "-");
        assertThat(rows.byFileName(fileName)).extracting(Row::state).allMatch("pending"::equals);
    }
}
