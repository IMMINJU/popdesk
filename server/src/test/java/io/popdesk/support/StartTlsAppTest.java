package io.popdesk.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Setup "starttls": SMTP to a server that requires STARTTLS, plain text not allowed (the production
 * default), and the webhook on. The JVM trusts the server's test certificate. Received mail and
 * webhook requests are cleared before each test.
 */
@TestPropertySource(properties = {
        "SMTP_USER=" + MailServer.USER,
        "SMTP_PASSWORD=" + MailServer.PASSWORD,
        "MAIL_FROM=" + MailServer.FROM,
})
public abstract class StartTlsAppTest extends AppTest {

    @DynamicPropertySource
    static void outsideSystems(DynamicPropertyRegistry registry) {
        registry.add("SMTP_HOST", () -> MailServer.HOST);
        registry.add("SMTP_PORT", () -> MailServer.shared().startTlsPort());
        registry.add("NOTIFY_WEBHOOK_URL", () -> WebhookReceiver.shared().url());
    }

    @Override
    protected final boolean smtpOn() {
        return true;
    }

    @BeforeEach
    void clearOutsideSystems() {
        mail().reset();
        webhook().reset();
    }
}
