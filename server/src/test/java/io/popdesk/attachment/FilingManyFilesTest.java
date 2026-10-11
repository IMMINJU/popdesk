package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A filing with many small files in one multipart request (verify.md V-13). The criteria limit a
 * filing's files by size only (10 MB each, 50 MB together); they name no number of files. The server
 * under the app has a limit of its own on the parts of one request, and a request over it must not
 * end as a server error.
 *
 * <p>Every file is a few bytes, so no size limit is near.
 */
class FilingManyFilesTest extends BareAppTest {

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("V-13 S0-AC-56 a filing with 10 files is 201 and all 10 are attached")
    void tenFiles() {
        Scene s = steps.scene();
        List<String> names = names(10);

        ApiResponse filed = file(s, Unique.text("Ten files"), names);

        assertThat(filed.status()).as("%s: %s", filed, filed.snippet()).isEqualTo(201);
        assertThat(list(s.customer(), filed.jsonText("key"))).extracting(n -> text(n, "fileName"))
                .as("file names on the issue").containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("V-13 S0-AC-56 a filing with 20 files is 201 and all 20 are attached")
    void twentyFiles() {
        Scene s = steps.scene();
        List<String> names = names(20);

        ApiResponse filed = file(s, Unique.text("Twenty files"), names);

        assertThat(filed.status()).as("%s: %s", filed, filed.snippet()).isEqualTo(201);
        assertThat(list(s.customer(), filed.jsonText("key"))).extracting(n -> text(n, "fileName"))
                .as("file names on the issue").containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("V-13 S0-AC-63 a filing with 200 files is never a server error: it is filed with all of them, or refused with an error body and nothing is filed")
    void twoHundredFiles() {
        Scene s = steps.scene();
        String title = Unique.text("Two hundred files");
        List<String> names = names(200);

        ApiResponse response = file(s, title, names);

        assertThat(response.status()).as("%s: %s", response, response.snippet()).isLessThan(500);
        if (response.status() == 201) {
            assertThat(s.customer().client().get(s.customer().api("/issues/" + response.jsonText("key")
                    + "/attachments")).query("size", 1).expect(200).json().path("totalElements").asLong())
                    .as("attachments on the issue").isEqualTo(200);
        } else {
            assertThat(response.status()).as("%s: %s", response, response.snippet()).isBetween(400, 499);
            assertThat(response.errorCode()).as("code of %s", response.snippet()).isNotBlank();
            assertThat(s.customer().client().get("/api/portal/issues").query("size", 100).expect(200).json()
                    .path("items").findValuesAsString("title")).as("titles of the customer's issues")
                    .doesNotContain(title);
            assertThat(new AttachmentRows(db).byFileName(names.getFirst())).as("attachment rows of the refused filing")
                    .isEmpty();
        }
    }

    private static List<String> names(int count) {
        String prefix = Unique.text("many");
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            names.add(prefix + "-" + i + ".txt");
        }
        return names;
    }

    /** One multipart filing: the issue part, then one small file per name. */
    private static ApiResponse file(Scene s, String title, List<String> fileNames) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", s.code());
        body.put("title", title);
        ApiRequest request = s.customer().client().post("/api/portal/issues").jsonPart("issue", body);
        for (String name : fileNames) {
            request.file("files", name, "text/plain", AttachmentSteps.text("content of " + name));
        }
        return request.send();
    }
}
