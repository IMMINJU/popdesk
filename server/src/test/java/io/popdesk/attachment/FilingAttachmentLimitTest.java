package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.MAX_FILE;
import static io.popdesk.attachment.AttachmentSteps.bytes;
import static io.popdesk.attachment.AttachmentSteps.list;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The 50 MB limit of the public attachments of one issue, at the filing itself: the files sent with a
 * new issue are that issue's first public attachments. {@link AttachmentLimitsTest} reaches the limit
 * with uploads onto an issue that exists.
 */
class FilingAttachmentLimitTest extends BareAppTest {

    /** 10 MB of content, sent five times in one filing. */
    private static final byte[] TEN_MB = bytes(MAX_FILE, 50);

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S0-AC-57 a filing whose files are exactly 50 MB together is filed with all of them")
    void exactlyFiftyMegabytes() {
        Scene s = steps.scene();

        ApiResponse filed = file(s, Unique.text("Exactly 50 MB"), new byte[0]);

        assertThat(filed.status()).as("%s %s", filed, filed.snippet()).isEqualTo(201);
        List<JsonNode> attached = list(s.customer(), filed.jsonText("key"));
        assertThat(attached).hasSize(5);
        assertThat(attached.stream().mapToLong(n -> n.path("size").asLong()).sum()).isEqualTo(5L * MAX_FILE);
    }

    @Test
    @DisplayName("S0-AC-57 a filing whose files are 50 MB and one byte together is 400 ISSUE_ATTACHMENTS_TOO_LARGE and files nothing")
    void fiftyMegabytesAndOneByte() {
        Scene s = steps.scene();
        String title = Unique.text("One byte over 50 MB");

        ApiResponse rejected = file(s, title, new byte[] {1});

        assertThat(rejected.status()).as("%s %s", rejected, rejected.snippet()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("ISSUE_ATTACHMENTS_TOO_LARGE");
        assertThat(s.customer().client().get("/api/portal/issues").query("size", 100).expect(200).json()
                .path("items").findValuesAsString("title")).as("titles of the issues the customer sees")
                .doesNotContain(title);
    }

    /** Files a new issue with five files of 10 MB and, when {@code extra} is not empty, one more file with it. */
    private static ApiResponse file(Scene s, String title, byte[] extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", title);
        body.put("content", "Five large files.");
        var request = s.customer().client().post("/api/portal/issues").jsonPart("issue", body);
        for (int i = 1; i <= 5; i++) {
            request.file("files", "part-" + i + ".bin", "application/octet-stream", TEN_MB);
        }
        if (extra.length > 0) {
            request.file("files", "extra.bin", "application/octet-stream", extra);
        }
        return request.send();
    }
}
