package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.WebhookKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The wording of the overdue SLA webhook messages (slice 8b) against the snapshot files of the HTTP tests
 * ({@code notify/__snapshots__/}), without an app, as {@link WebhookTextTest} does for the earlier kinds. Here both
 * installation languages are checked; the HTTP test {@code sla.SlaAlertMailTest} sees the {@code en} message as sent.
 * The kind is looked up by its stored name, so a missing kind fails as an assertion.
 */
class SlaWebhookTextTest {

    private static final String TITLE = "Printer \"B2\" jams\nafter toner change";

    private static final String ORIGIN = "http://console.popdesk.test";

    @ParameterizedTest(name = "{0}-{1}")
    @CsvSource({
            "en, sla-response-breached, SLA_RESPONSE_BREACHED", "ko, sla-response-breached, SLA_RESPONSE_BREACHED",
            "en, sla-resolution-breached, SLA_RESOLUTION_BREACHED",
            "ko, sla-resolution-breached, SLA_RESOLUTION_BREACHED"})
    @DisplayName("S8B-AC-18 the body for an overdue first response and an overdue resolution goal is the snapshot of "
            + "its language, character for character")
    void bodyIsTheSnapshot(String language, String name, String kindName) throws IOException {
        Optional<WebhookKind> kind = Arrays.stream(WebhookKind.values()).filter(k -> k.name().equals(kindName))
                .findFirst();
        assertThat(kind).as("webhook kind %s", kindName).isPresent();

        String text = WebhookSender.text(kind.get(), new Issue("{key}", TITLE, "{code}"), language.equals("ko"),
                ORIGIN);
        String body = ("{\"text\":" + WebhookSender.json(text) + "}").replace(ORIGIN + "/issues/{key}", "{link}");

        assertThat(body).isEqualTo(snapshot(language + "-" + name + ".json"));
    }

    private static String snapshot(String file) throws IOException {
        try (InputStream in = SlaWebhookTextTest.class.getResourceAsStream("/notify/__snapshots__/" + file)) {
            assertThat(in).as("snapshot file %s", file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
