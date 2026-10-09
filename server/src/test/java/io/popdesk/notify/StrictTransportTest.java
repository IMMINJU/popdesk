package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.StartTlsAppTest;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Production settings pointed at servers that cannot meet them: {@code SMTP_PORT} is GreenMail's
 * plain port, which offers no STARTTLS, and {@code SMTP_ALLOW_PLAINTEXT} is not set; the webhook URL
 * is https. A context of its own. People cannot be invited here (their mail never goes out), so the
 * tests use the first admin only.
 */
@Import(StrictTransportTest.StrictSettings.class)
class StrictTransportTest extends StartTlsAppTest {

    @Autowired
    Environment environment;

    /**
     * The two keys. A {@code @DynamicPropertySource} method here would lose to the one in
     * {@link StartTlsAppTest}; a registrar bean is applied after it and wins.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StrictSettings {

        @Bean
        static DynamicPropertyRegistrar strictSettings() {
            return registry -> {
                registry.add("SMTP_PORT", () -> MailServer.shared().plainPort());
                registry.add("NOTIFY_WEBHOOK_URL", () -> "https://127.0.0.1:" + WebhookReceiver.shared().port()
                        + "/hook");
            };
        }
    }

    @Test
    @DisplayName("S0-AC-74 without SMTP_ALLOW_PLAINTEXT the app sends nothing to a server that offers no STARTTLS")
    void noPlainTextFallback() {
        assertThat(environment.getProperty("SMTP_PORT")).as("SMTP_PORT").isEqualTo(String.valueOf(mail().plainPort()));
        assertThat(environment.getProperty("NOTIFY_WEBHOOK_URL")).as("NOTIFY_WEBHOOK_URL").startsWith("https://");
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("plain");

        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Plain " + Unique.number(), "role", "AGENT")).expect(201);
        Wait.holds("no mail over plain SMTP", Duration.ofSeconds(3), () -> mail().messagesTo(email).isEmpty());
        clock.advance(Duration.ofSeconds(5));
        Wait.holds("no mail over plain SMTP on retry", Duration.ofSeconds(3), () -> mail().messagesTo(email).isEmpty());

        ApiResponse status = admin.client().get("/api/console/system/status").expect(200);
        assertThat(status.json().path("mail").path("enabled").asBoolean(false)).as("mail on").isTrue();
        assertThat(status.json().path("mail").path("lastSuccessAt").isNull()).as("no successful mail").isTrue();
    }

    @Test
    @DisplayName("S0-AC-76 an https webhook URL is taken without the http warning")
    void httpsWebhookWithoutWarning() {
        Session admin = fixtures.signInAdmin();

        ApiResponse status = admin.client().get("/api/console/system/status").expect(200);

        assertThat(status.json().path("webhook").path("enabled").asBoolean(false)).as("webhook on").isTrue();
        assertThat(log.lines()).as("startup warnings about NOTIFY_WEBHOOK_URL")
                .noneMatch(l -> l.contains("WARN") && l.contains("NOTIFY_WEBHOOK_URL"));
    }
}
