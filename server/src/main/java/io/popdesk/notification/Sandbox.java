package io.popdesk.notification;

import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The sandbox settings (docs/operate/configuration.md), read here and nowhere else: whether this is a
 * sandbox ({@code POPDESK_SANDBOX}), and the one address its email goes to
 * ({@code POPDESK_SANDBOX_MAIL_TO}). A sandbox is a copy of a real installation, so nothing may leave
 * it for the real receivers (S4B-AC-01 to S4B-AC-04): the webhook and the outside copy of the audit log
 * are off, and email goes to that one address only, or nowhere without one. Mail, the webhook, the
 * outside copy, the startup warning, and the system status all ask this class.
 *
 * <p>A sandbox is the value {@code true}, whatever its case and the spaces around it; {@code 1},
 * {@code yes}, and an empty value are none. Outside a sandbox the address is not read at all. In a
 * sandbox an empty address is as none, and one that is no single email address stops the start
 * (S4B-AC-06): a sandbox that came up with a wrong address would send its email nobody knows where.
 */
@Component
public class Sandbox {

    public static final String KEY = "POPDESK_SANDBOX";
    public static final String MAIL_TO_KEY = "POPDESK_SANDBOX_MAIL_TO";

    /** One address: something on each side of one {@code @}, and nothing that could start a second address. */
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@,;:<>()\"\\\\]+@[^\\s@,;:<>()\"\\\\]+");

    private final boolean on;
    private final String mailTo;

    @Autowired
    Sandbox(Environment environment) {
        this(environment::getProperty);
    }

    private Sandbox(UnaryOperator<String> setting) {
        this.on = on(setting);
        String address = on ? setting.apply(MAIL_TO_KEY) : null;
        this.mailTo = address == null || address.isBlank() ? null : address.strip();
        if (mailTo != null && !ADDRESS.matcher(mailTo).matches()) {
            // Never the value: it can be most of a real address.
            throw new IllegalStateException(MAIL_TO_KEY + " is not an email address. In a sandbox ("
                    + KEY + "=true) it must be one address, or empty to send no email at all.");
        }
    }

    /**
     * The sandbox of these settings.
     *
     * @param setting reads a setting by its key; null when it is not set
     * @throws IllegalStateException for a sandbox whose address is no single email address
     */
    public static Sandbox of(UnaryOperator<String> setting) {
        return new Sandbox(setting);
    }

    /**
     * Whether these settings make a sandbox, without a look at the address: for code that runs outside
     * the app's context (the commands in the container).
     *
     * @param setting reads a setting by its key; null when it is not set
     */
    public static boolean on(UnaryOperator<String> setting) {
        String value = setting.apply(KEY);
        return value != null && value.strip().equalsIgnoreCase("true");
    }

    /** Whether this is a sandbox. Then the webhook and the outside copy of the audit log are off. */
    public boolean on() {
        return on;
    }

    /** The one address a sandbox sends all email to; empty outside a sandbox and in one without an address. */
    public Optional<String> mailTo() {
        return Optional.ofNullable(mailTo);
    }

    /** Whether the sandbox turns email off: it has no address to send it to. */
    public boolean mailOff() {
        return on && mailTo == null;
    }
}
