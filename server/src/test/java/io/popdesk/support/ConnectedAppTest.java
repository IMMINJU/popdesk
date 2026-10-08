package io.popdesk.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Setup "connected": SMTP to GreenMail's plain port with {@code SMTP_ALLOW_PLAINTEXT=true} (like
 * the Mailpit dev setup), and {@code NOTIFY_WEBHOOK_URL} pointing at {@link WebhookReceiver}.
 * Received mail and webhook requests are cleared before each test.
 *
 * <p>The static keys here can be changed with {@code @TestPropertySource} on a subclass. The
 * dynamic ones (SMTP host and port, webhook URL) win over {@code @TestPropertySource}; change them
 * with your own {@code @DynamicPropertySource}.
 */
@TestPropertySource(properties = {
        "SMTP_USER=" + MailServer.USER,
        "SMTP_PASSWORD=" + MailServer.PASSWORD,
        "MAIL_FROM=" + MailServer.FROM,
        "SMTP_ALLOW_PLAINTEXT=true",
})
public abstract class ConnectedAppTest extends AppTest {

    @DynamicPropertySource
    static void outsideSystems(DynamicPropertyRegistry registry) {
        registry.add("SMTP_HOST", () -> MailServer.HOST);
        registry.add("SMTP_PORT", () -> MailServer.shared().plainPort());
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
