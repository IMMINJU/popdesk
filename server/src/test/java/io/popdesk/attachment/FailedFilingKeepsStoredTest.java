package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.download;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentRows.Row;
import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the step after a failed filing leaves alone (verify.md V-9): it removes the contents of files
 * whose rows the cleanup ended, never those of a {@code stored} file. A filing can fail for the caller
 * after its transaction was committed; the issue then has the file and must keep its contents.
 * {@link FilingCleanupRaceTest} has the case where the contents go.
 */
class FailedFilingKeepsStoredTest extends BareAppTest {

    @Autowired
    AttachmentService service;

    private AttachmentSteps steps;
    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("V-9 S0-AC-63 the step after a failed filing keeps the contents of a stored file and takes an ID without a row")
    void storedFileKeepsItsContents() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        byte[] content = bytes(1_000, Unique.number());
        ApiResponse uploaded = upload(s.customer(), issue.key(), Unique.text("stored") + ".bin", content);
        assertThat(uploaded.status()).as("upload: %s", uploaded.snippet()).isEqualTo(201);
        String id = text(uploaded.json(), "id");
        assertThat(rows.findById(id).map(Row::state)).as("state of the uploaded file's row").contains("stored");

        service.discardEnded(List.of(UUID.fromString(id), UUID.randomUUID()));

        assertThat(rows.contentCopies(content)).as("rows of attachment_content with the stored file's content")
                .isEqualTo(1);
        ApiResponse downloaded = download(s.customer(), issue.key(), id);
        assertThat(downloaded.status()).as("download of the stored file: %s", downloaded.snippet()).isEqualTo(200);
        assertThat(downloaded.body()).as("downloaded bytes").isEqualTo(content);
    }
}
