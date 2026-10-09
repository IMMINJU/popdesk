package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static io.popdesk.notify.NotifySteps.urlForms;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.WebhookReceiver;
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
 */
class WebhookSecretTest extends ConnectedAppTest {

    @Autowired
    Environment environment;

    private NotifySteps steps;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
    }

    private static void assertNoUrl(String where, String text) {
        for (String form : urlForms(webhookUrl())) {
            assertThat(text).as(where + " holds the webhook URL as " + form).doesNotContainIgnoringCase(form);
        }
    }

    private static String webhookUrl() {
        return WebhookReceiver.shared().url();
    }

    /** Files an issue while the receiver answers 500 and waits for the failed send. */
    private Issue failedSend(Scene s) {
        webhook().respondWith(500);
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        awaitWebhooks(webhook(), issue.key(), 1);
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
