package io.popdesk.notification.outbox;

import io.popdesk.notification.AuditCopyRows;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup warnings about unencrypted outgoing channels (D-51): plain-text SMTP allowed (S0-AC-74), a
 * webhook URL that is {@code http} (S0-AC-76), and an address of the audit log's outside copy that is
 * {@code http}, once more when a token goes with it (D-54). No line holds a URL, a password, or the
 * token.
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
        if (http(environment.getProperty("NOTIFY_WEBHOOK_URL"))) {
            log.warn("NOTIFY_WEBHOOK_URL uses http, so webhook messages are sent without encryption. Use https when "
                    + "the receiver is outside your network.");
        }
        if (AuditCopyRows.enabled(environment::getProperty) && http(environment.getProperty(AuditCopyRows.URL_KEY))) {
            log.warn("{} uses http, so audit log entries, with the names and email addresses in them, are sent "
                    + "without encryption. Use https when the receiver is outside your network.",
                    AuditCopyRows.URL_KEY);
            String token = environment.getProperty(AuditCopyRows.TOKEN_KEY);
            if (token != null && !token.isBlank()) {
                log.warn("{} is set while {} uses http: the token is sent without encryption with every entry. "
                        + "Use https, or a token that is of no use outside your network.", AuditCopyRows.TOKEN_KEY,
                        AuditCopyRows.URL_KEY);
            }
        }
    }

    private static boolean http(String url) {
        return url != null && url.strip().toLowerCase(Locale.ROOT).startsWith("http://");
    }
}
