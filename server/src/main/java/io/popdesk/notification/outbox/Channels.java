package io.popdesk.notification.outbox;

import io.popdesk.notification.AuditCopyRows;
import io.popdesk.notification.Sandbox;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Which outgoing channels are on (S4B-AC-08): a channel is on when it is configured and the
 * {@link Sandbox} did not turn it off. Mail needs {@code SMTP_HOST} and, in a sandbox, an address;
 * the webhook needs {@code NOTIFY_WEBHOOK_URL} and the outside copy of the audit log
 * {@code AUDIT_FORWARD_URL}, and neither is on in a sandbox. A channel that is off sends nothing,
 * takes no row back into its queue, and raises no alert.
 */
@Component
public class Channels {

    static final String WEBHOOK_URL_KEY = "NOTIFY_WEBHOOK_URL";

    private final SmtpMail smtp;
    private final Environment environment;
    private final Sandbox sandbox;

    Channels(SmtpMail smtp, Environment environment, Sandbox sandbox) {
        this.smtp = smtp;
        this.environment = environment;
        this.sandbox = sandbox;
    }

    /** @param channel {@link OutboxRepository#MAIL}, {@link OutboxRepository#WEBHOOK} or {@link OutboxRepository#AUDIT_FORWARD} */
    public boolean enabled(String channel) {
        return switch (channel) {
            case OutboxRepository.MAIL -> smtp.enabled();
            case OutboxRepository.WEBHOOK -> webhookOn(environment, sandbox);
            case OutboxRepository.AUDIT_FORWARD -> AuditCopyRows.enabled(environment::getProperty);
            default -> throw new IllegalArgumentException("No such channel");
        };
    }

    /** Whether the webhook is sent: its address is set and this is no sandbox (S4B-AC-04). */
    static boolean webhookOn(Environment environment, Sandbox sandbox) {
        String url = environment.getProperty(WEBHOOK_URL_KEY);
        return url != null && !url.isBlank() && !sandbox.on();
    }
}
