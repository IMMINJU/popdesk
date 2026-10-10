package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The longest part headers a file within the limits can have (verify.md V-16): a name of 255
 * characters that take 4 bytes each in UTF-8, 1,020 bytes, sent with a content type of 255 characters.
 * {@code server.tomcat.max-part-header-size} was chosen to hold these; {@link LongFileNameTest} has
 * the Korean names.
 */
class FourByteFileNameTest extends BareAppTest {

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("V-16 S0-AC-57 a file whose name is 255 four-byte characters, sent with a content type of 255 characters, is attached with 201 and keeps the name")
    void uploadWithFourByteNameAndLongContentType() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String name = "📎".repeat(251) + ".txt";
        String contentType = "application/x-" + "a".repeat(255 - "application/x-".length());

        ApiResponse uploaded = s.customer().client().post(s.customer().api("/issues/" + issue.key() + "/attachments"))
                .file("file", name, contentType, AttachmentSteps.text("content"))
                .send();

        assertThat(uploaded.status()).as("upload with a file name of %s characters, %s bytes in UTF-8: %s",
                name.codePointCount(0, name.length()), name.getBytes(StandardCharsets.UTF_8).length,
                uploaded.snippet()).isEqualTo(201);
        assertThat(text(uploaded.json(), "fileName")).as("file name in the response").isEqualTo(name);
        assertThat(list(s.customer(), issue.key())).extracting(n -> text(n, "fileName"))
                .as("file names on the issue").containsExactly(name);
    }
}
