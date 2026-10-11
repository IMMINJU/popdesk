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
 * A filing with several small files in one multipart request (verify.md V-13). Every file is a few
 * bytes, so no size limit is near. The number of files a filing may have, and what a filing over it
 * gets, are checked by {@code FilingFileCountTest}.
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
