package io.popdesk.helpdesk.audit;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * How long audit entries are kept, {@code POPDESK_AUDIT_RETENTION} (docs/operate/configuration.md,
 * AUDIT-4): an ISO-8601 duration of days and time such as {@code P365D} or {@code PT720H}, read here
 * and nowhere else. Without the key it is {@code P365D}. A value that is not such a duration, or one
 * shorter than {@code P30D}, is not taken: the app then takes no requests until it is set right, as
 * for {@code POPDESK_LOCALE} (S1B-AC-20), and deletes no entry meanwhile.
 */
@Component
public class AuditRetention {

    public static final String KEY = "POPDESK_AUDIT_RETENTION";

    static final Duration DEFAULT = Duration.ofDays(365);

    /** The shortest period taken: a typo must not empty the log in a day. */
    static final Duration SHORTEST = Duration.ofDays(30);

    private final Duration period;

    AuditRetention(Environment environment) {
        this.period = parse(environment.getProperty(KEY)).orElse(null);
    }

    /** The period for a value popdesk takes, {@code P365D} for no value at all; empty otherwise. */
    static Optional<Duration> parse(String configured) {
        if (configured == null) {
            return Optional.of(DEFAULT);
        }
        Duration period;
        try {
            // A day is 24 hours: the period is a difference of UTC times (S1B-AC-22).
            period = Duration.parse(configured.strip());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        return period.compareTo(SHORTEST) < 0 ? Optional.empty() : Optional.of(period);
    }

    /** Whether {@code POPDESK_AUDIT_RETENTION} is a value popdesk takes. */
    public boolean valid() {
        return period != null;
    }

    /** The period; empty while the value is not valid. */
    Optional<Duration> period() {
        return Optional.ofNullable(period);
    }
}
