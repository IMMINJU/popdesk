package io.popdesk.helpdesk.mailin;

import io.popdesk.notification.MailInState;
import io.popdesk.notification.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The mail-in settings of this start ({@link MailInSettings}, S12-AC-01 to S12-AC-03): read once and checked before
 * the app takes requests, without reaching for the IMAP server. Wrong settings stop the start with one log line per
 * problem, naming the key and never a value. At startup one warning while plain text is allowed (D-51), and one in a
 * sandbox with the settings, saying that receiving email is off. No line holds the host, the user, the password, a
 * folder, or the inbound address.
 */
@Component
class MailInSetup implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MailInSetup.class);

    private final MailInSettings settings;

    MailInSetup(Environment environment, Sandbox sandbox) {
        this.settings = MailInSettings.read(environment::getProperty, sandbox.on());
    }

    @Override
    public void afterPropertiesSet() {
        if (!settings.problems().isEmpty()) {
            for (String problem : settings.problems()) {
                log.error("{} popdesk did not start (docs/operate/configuration.md, \"Receiving email\").", problem);
            }
            throw new IllegalStateException("The mail-in settings are wrong: " + String.join(" ", settings.problems()));
        }
        if (settings.enabled()) {
            log.info("Receiving email is on.");
        } else if (settings.offReason() == MailInState.OffReason.NOT_SET) {
            log.info("Receiving email is off ({} is not set).", MailInSettings.HOST);
        }
    }

    @EventListener(ApplicationStartedEvent.class)
    void started() {
        if (settings.offReason() == MailInState.OffReason.SANDBOX) {
            log.warn("{} is true, so receiving email is off although the IMAP settings are there: no round reads the "
                    + "mailbox, and notification emails get no Reply-To.", Sandbox.KEY);
        }
        if (settings.enabled() && settings.plainTextPossible()) {
            log.warn("{} is true: when the IMAP server does not offer STARTTLS, email is read without encryption, "
                    + "password included. Use it only for a local test server.", MailInSettings.ALLOW_PLAINTEXT);
        }
    }

    MailInSettings settings() {
        return settings;
    }
}
