package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.WebhookKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The wording of the webhook messages against the snapshot files of the HTTP tests
 * ({@code notify/__snapshots__/}), without an app: the request body {@link WebhookSender} would post
 * for an issue whose key, company code, and link are the placeholders of those files.
 */
class WebhookTextTest {

    /** The title of the snapshots; its line break becomes a space in the message. */
    private static final String TITLE = "Printer \"B2\" jams\nafter toner change";

    private static final String ORIGIN = "http://console.popdesk.test";

    @ParameterizedTest(name = "{0}-{1}")
    @CsvSource({
            "en, issue-created, ISSUE_CREATED", "ko, issue-created, ISSUE_CREATED",
            "en, customer-comment, CUSTOMER_COMMENT", "ko, customer-comment, CUSTOMER_COMMENT",
            "en, issue-resolved, ISSUE_RESOLVED", "ko, issue-resolved, ISSUE_RESOLVED",
            "en, issue-closed, ISSUE_CLOSED", "ko, issue-closed, ISSUE_CLOSED",
            "en, issue-reopened, ISSUE_REOPENED", "ko, issue-reopened, ISSUE_REOPENED"})
    @DisplayName("S3-AC-74 the body for a new, commented, resolved, closed, and reopened issue is the snapshot of "
            + "its language, character for character")
    void bodyIsTheSnapshot(String language, String name, WebhookKind kind) throws IOException {
        String text = WebhookSender.text(kind, new Issue("{key}", TITLE, "{code}"), language.equals("ko"), ORIGIN);

        String body = ("{\"text\":" + WebhookSender.json(text) + "}").replace(ORIGIN + "/issues/{key}", "{link}");

        assertThat(body).isEqualTo(snapshot(language + "-" + name + ".json"));
    }

    private static String snapshot(String file) throws IOException {
        try (InputStream in = WebhookTextTest.class.getResourceAsStream("/notify/__snapshots__/" + file)) {
            assertThat(in).as("snapshot file %s", file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
