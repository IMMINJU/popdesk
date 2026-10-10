package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MB;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The team quota ({@code POPDESK_ATTACHMENT_QUOTA}) for the files sent with a new issue.
 * {@link AttachmentQuotaTest} reaches the quota with uploads onto an issue that exists.
 *
 * <p>This class sets the same key as {@link AttachmentQuotaTest} and so shares its context and
 * database, where that class counts every stored byte. The file here is larger than the whole quota:
 * it is refused whatever is stored already, and nothing of it is stored.
 */
@TestPropertySource(properties = "POPDESK_ATTACHMENT_QUOTA=1MB")
class FilingQuotaTest extends BareAppTest {

    private AttachmentRows rows;

    @BeforeEach
    void setUp() {
        rows = new AttachmentRows(db);
    }

    @Test
    @DisplayName("S0-AC-58 a filing with a file larger than the quota is 400 ATTACHMENT_QUOTA_EXCEEDED and files nothing")
    void filingOverQuota() {
        Scene s = new AttachmentSteps(fixtures).scene();
        String title = Unique.text("Over the quota");
        byte[] content = bytes(MB + 1, Unique.number());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", title);
        body.put("content", "One file over the quota.");

        ApiResponse rejected = s.customer().client().post("/api/portal/issues").jsonPart("issue", body)
                .file("files", "over-quota.bin", "application/octet-stream", content).send();

        assertThat(rejected.status()).as("%s %s", rejected, rejected.snippet()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("ATTACHMENT_QUOTA_EXCEEDED");
        assertThat(s.customer().client().get("/api/portal/issues").query("size", 100).expect(200).json()
                .path("items").findValuesAsString("title")).as("titles of the issues the customer sees")
                .doesNotContain(title);
        assertThat(rows.contentCopies(content)).as("rows of attachment_content with the refused file").isZero();
    }
}
