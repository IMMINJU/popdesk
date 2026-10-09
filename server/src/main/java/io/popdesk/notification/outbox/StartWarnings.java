package io.popdesk.notification.outbox;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup warnings about unencrypted outgoing channels (D-51): plain-text SMTP allowed (S0-AC-74), and
 * a webhook URL that is {@code http} (S0-AC-76). Neither line holds the URL or a password.
 */
@Component
class StartWarnings {

    private static final Logger log = LoggerFactory.getLogger(StartWarnings.class);

    private final Environment environment;
    private final SmtpMail smtp;

    StartWarnings(Environment environment, SmtpMail smtp) {
        this.environment = environment;
        this.smtp = smtp;
    }

    @EventListener(ApplicationStartedEvent.class)
    void started() {
        if (smtp.enabled() && smtp.plainTextAllowed()) {
            log.warn("SMTP_ALLOW_PLAINTEXT is true: when the SMTP server does not offer STARTTLS, email is sent "
                    + "without encryption, password included. Use it only for a local test server.");
        }
        String url = environment.getProperty("NOTIFY_WEBHOOK_URL");
        if (url != null && url.strip().toLowerCase(Locale.ROOT).startsWith("http://")) {
            log.warn("NOTIFY_WEBHOOK_URL uses http, so webhook messages are sent without encryption. Use https when "
                    + "the receiver is outside your network.");
        }
    }
}
