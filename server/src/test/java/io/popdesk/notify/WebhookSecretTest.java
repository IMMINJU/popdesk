package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static io.popdesk.notify.NotifySteps.urlForms;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * The webhook URL is a secret (configuration.md): it is nowhere in the log, the API, or the
 * metrics, even after a failed send. This setup's URL is {@code http://127.0.0.1:<port>/hook}, so
 * the app warns at startup that it is not https, without the URL.
 *
 * <p>A row here is never {@code failed}: that takes 24 hours of the app clock, which this shared
 * context does not move. {@code RetryTest.failedRowShowsNoUrl} checks the failed deliveries of a row
 * that is.
 */
class WebhookSecretTest extends ConnectedAppTest {

    @Autowired
    Environment environment;

    private NotifySteps steps;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
    }

    private static void assertNoUrl(String where, String text) {
        for (String form : urlForms(webhookUrl())) {
            assertThat(text).as(where + " holds the webhook URL as " + form).doesNotContainIgnoringCase(form);
        }
    }

    private static String webhookUrl() {
        return WebhookReceiver.shared().url();
    }

    /**
     * Files an issue while the receiver answers 500, and waits until the app has handled two failed
     * sends: the queue row holds the next attempt 2 seconds after the second one. The receiver records
     * a request before it answers, so the first request alone does not mean the app has seen the 500
     * and written what it writes about it.
     */
    private Issue failedSend(Scene s) {
        webhook().respondWith(500);
        Snapshot before = rows.snapshot();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Instant firstFailure = clock.instant();
        awaitWebhooks(webhook(), issue.key(), 1);
        Row row = Wait.until("the queue row of " + issue.key() + " with its next attempt 1 second later", () -> {
            List<Row> added = rows.added(before).stream()
                    .filter(r -> !r.columnsAt(firstFailure.plusSeconds(1)).isEmpty()).toList();
            return added.size() == 1 ? added.getFirst() : null;
        });
        Instant secondFailure = clock.advance(Duration.ofSeconds(1));
        awaitWebhooks(webhook(), issue.key(), 2);
        Wait.until("the second failed send of " + issue.key() + " handled, next attempt 2 seconds later", () -> {
            Row r = rows.reread(row);
            return r != null && !r.columnsAt(secondFailure.plusSeconds(2)).isEmpty() ? r : null;
        });
        return issue;
    }

    @Test
    @DisplayName("S0-AC-76 the app warns at startup that the webhook URL is http, without the URL")
    void httpWarningWithoutUrl() {
        List<String> warnings = log.lines().stream()
                .filter(l -> l.contains("WARN") && l.contains("NOTIFY_WEBHOOK_URL"))
                .toList();

        assertThat(warnings).as("startup warnings about NOTIFY_WEBHOOK_URL").isNotEmpty();
        warnings.forEach(w -> assertNoUrl("the warning", w));
    }

    @Test
    @DisplayName("S0-AC-76 after a failed webhook send the log has no webhook URL")
    void logHasNoUrlAfterFailure() {
        Scene s = steps.scene();
        failedSend(s);

        assertNoUrl("the log", log.all());
    }

    @Test
    @DisplayName("S0-AC-76 system status and failed deliveries show no webhook URL")
    void apiHasNoUrl() {
        Scene s = steps.scene();
        failedSend(s);

        ApiResponse status = s.admin().client().get("/api/console/system/status").expect(200);
        ApiResponse failed = s.admin().client().get("/api/console/system/failed-deliveries").expect(200);

        assertThat(status.json().path("webhook").path("enabled").asBoolean(false)).as("webhook on").isTrue();
        assertNoUrl("system status", status.text());
        assertNoUrl("failed deliveries", failed.text());
    }

    @Test
    @DisplayName("S0-AC-76 the metrics have no webhook URL in names, labels, or values")
    void metricsHaveNoUrl() {
        Scene s = steps.scene();
        failedSend(s);
        String managementPort = environment.getProperty("local.management.port");
        assertThat(managementPort).as("local.management.port").isNotNull();

        ApiResponse metrics = new ApiClient("localhost", Integer.parseInt(managementPort))
                .get("/actuator/prometheus").expect(200);

        assertThat(metrics.text()).as("metrics").isNotBlank();
        assertNoUrl("the metrics", metrics.text());
    }
}
