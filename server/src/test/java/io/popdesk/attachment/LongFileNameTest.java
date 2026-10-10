package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static io.popdesk.attachment.AttachmentSteps.upload;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A file name of 255 characters, the longest the criteria allow (S0-AC-57), in Korean (verify.md
 * V-16). Lengths are counted in code points, but a multipart part carries the name in its headers as
 * UTF-8: a name of 255 Korean characters is over 750 bytes, more than the server's default limit of 512 bytes for
 * the headers of one part. The file must be attached all the same, with its name as sent.
 *
 * <p>A name of 255 ASCII characters, which stays under that limit, is the case to compare with.
 */
class LongFileNameTest extends BareAppTest {

    private static final int LONGEST = 255;

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("V-16 S0-AC-57 a filing with a file whose name is 255 Korean characters is 201 and keeps the name")
    void filingWithKoreanName() {
        assertFiledWithName(longest('가'));
    }

    @Test
    @DisplayName("V-16 S0-AC-57 a file whose name is 255 Korean characters is attached to an issue with 201 and keeps the name")
    void uploadWithKoreanName() {
        assertUploadedWithName(longest('나'));
    }

    @Test
    @DisplayName("V-16 S0-AC-57 a filing with a file whose name is 255 ASCII characters is 201 and keeps the name")
    void filingWithAsciiName() {
        assertFiledWithName(longest('a'));
    }

    @Test
    @DisplayName("V-16 S0-AC-57 a file whose name is 255 ASCII characters is attached to an issue with 201 and keeps the name")
    void uploadWithAsciiName() {
        assertUploadedWithName(longest('b'));
    }

    private void assertFiledWithName(String name) {
        Scene s = steps.scene();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", Unique.text("Long file name"));

        ApiResponse filed = s.customer().client().post("/api/portal/issues").jsonPart("issue", body)
                .file("files", name, "text/plain", AttachmentSteps.text("content")).send();

        assertThat(filed.status()).as("filing with a file name of %s characters, %s bytes in UTF-8: %s",
                name.codePointCount(0, name.length()), utf8Length(name), filed.snippet()).isEqualTo(201);
        assertThat(list(s.customer(), filed.jsonText("key"))).extracting(n -> text(n, "fileName"))
                .as("file names on the issue").containsExactly(name);
    }

    private void assertUploadedWithName(String name) {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse uploaded = upload(s.customer(), issue.key(), name, AttachmentSteps.text("content"));

        assertThat(uploaded.status()).as("upload with a file name of %s characters, %s bytes in UTF-8: %s",
                name.codePointCount(0, name.length()), utf8Length(name), uploaded.snippet()).isEqualTo(201);
        assertThat(text(uploaded.json(), "fileName")).as("file name in the response").isEqualTo(name);
        assertThat(list(s.customer(), issue.key())).extracting(n -> text(n, "fileName"))
                .as("file names on the issue").containsExactly(name);
    }

    /** A name of exactly 255 characters: {@code c} repeated, ending in {@code .txt}. */
    private static String longest(char c) {
        String extension = ".txt";
        return String.valueOf(c).repeat(LONGEST - extension.length()) + extension;
    }

    private static int utf8Length(String name) {
        return name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
