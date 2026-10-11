package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentSteps.list;
import static io.popdesk.attachment.AttachmentSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many files one filing may carry, and that those files count toward the upload limit of the
 * account (30 in 60 seconds of the app's clock). Every file is a few bytes, so no size limit is
 * near. The test clock stands still, so the files of one test fall in one window.
 */
class FilingFileCountTest extends BareAppTest {

    private AttachmentSteps steps;

    @BeforeEach
    void setUp() {
        steps = new AttachmentSteps(fixtures);
    }

    @Test
    @DisplayName("S1A-AC-65 a filing with 20 files is 201 and all 20 are attached")
    void twentyFiles() {
        Scene s = steps.scene();
        List<String> names = names(20);

        ApiResponse filed = file(s, Unique.text("Twenty files"), names);

        assertThat(filed.status()).as("%s: %s", filed, filed.snippet()).isEqualTo(201);
        assertThat(list(s.customer(), filed.jsonText("key"))).extracting(n -> text(n, "fileName"))
                .as("file names on the issue").containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("S1A-AC-65 a filing with 21 files is 413 PAYLOAD_TOO_LARGE and files nothing: no issue, no "
            + "attachment row")
    void twentyOneFiles() {
        Scene s = steps.scene();
        String title = Unique.text("Twenty-one files");
        List<String> names = names(21);

        ApiResponse refused = file(s, title, names);

        assertThat(refused.status()).as("%s: %s", refused, refused.snippet()).isEqualTo(413);
        assertThat(refused.errorCode()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(titles(s)).as("titles of the customer's issues").doesNotContain(title);
        assertThat(new AttachmentRows(db).byFileName(names.getFirst())).as("attachment rows of the refused filing")
                .isEmpty();
    }

    @Test
    @DisplayName("S1A-AC-66 the files of a filing count toward the 30 uploads in 60 seconds: after a filing with "
            + "16 files, a second one with 16 is 429 RATE_LIMITED with Retry-After and files nothing; 60 seconds "
            + "later it is filed")
    void filedFilesCountTowardTheUploadLimit() {
        Scene s = steps.scene();
        String secondTitle = Unique.text("Second sixteen");
        List<String> secondNames = names(16);
        ApiResponse first = file(s, Unique.text("First sixteen"), names(16));
        assertThat(first.status()).as("%s: %s", first, first.snippet()).isEqualTo(201);

        ApiResponse second = file(s, secondTitle, secondNames);

        assertThat(second.status()).as("%s: %s", second, second.snippet()).isEqualTo(429);
        assertThat(second.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(second.header("Retry-After")).as("Retry-After").isNotBlank();
        assertThat(titles(s)).as("titles of the customer's issues").doesNotContain(secondTitle);
        assertThat(new AttachmentRows(db).byFileName(secondNames.getFirst()))
                .as("attachment rows of the limited filing").isEmpty();
        assertThat(list(s.customer(), first.jsonText("key"))).as("files of the first filing").hasSize(16);

        clock.advance(Duration.ofSeconds(61));
        ApiResponse later = file(s, secondTitle, secondNames);

        assertThat(later.status()).as("%s: %s", later, later.snippet()).isEqualTo(201);
        assertThat(list(s.customer(), later.jsonText("key"))).hasSize(16);
    }

    private static List<String> titles(Scene s) {
        return s.customer().client().get("/api/portal/issues").query("size", 100).expect(200).json()
                .path("items").findValuesAsString("title");
    }

    private static List<String> names(int count) {
        String prefix = Unique.text("count");
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
