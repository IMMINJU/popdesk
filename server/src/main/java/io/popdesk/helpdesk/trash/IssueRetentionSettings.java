package io.popdesk.helpdesk.trash;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Automatic issue cleanup's setting, {@code POPDESK_ISSUE_RETENTION} (PII-6, S10B-AC-18,
 * docs/operate/configuration.md): an ISO-8601 duration of days and time such as {@code P1095D} or
 * {@code PT2160H}, read here and nowhere else, as {@code POPDESK_AUDIT_RETENTION} is read. Without the key, or
 * with an empty value, the cleanup is off. A value that is not such a duration (years and months, {@code P1Y} or
 * {@code P3M}, are not), or one shorter than {@code P90D}, stops the start (design.md 0절 9): unlike the audit
 * retention, which keeps the app up and takes no requests, a wrong value here must not reach a run that moves
 * issues.
 */
@Component
class IssueRetentionSettings implements InitializingBean {

    static final String KEY = "POPDESK_ISSUE_RETENTION";

    /** The shortest period taken: a typo must not move the issues of the last months to the trash. */
    static final Duration SHORTEST = Duration.ofDays(90);

    private static final Logger log = LoggerFactory.getLogger(IssueRetentionSettings.class);

    private final String configured;
    private Duration period;

    IssueRetentionSettings(Environment environment) {
        this.configured = environment.getProperty(KEY);
    }

    @Override
    public void afterPropertiesSet() {
        if (configured == null || configured.isBlank()) {
            log.info("Automatic issue cleanup is off ({} is not set).", KEY);
            return;
        }
        String value = configured.strip();
        Duration parsed;
        try {
            // A day is 24 hours: the period is a difference of UTC times (TIME-1).
            parsed = Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw stop(KEY + "=" + value + " is not an ISO-8601 duration of days and hours such as P1095D or "
                    + "PT2160H (years and months are not taken). popdesk did not start. Set it right, or remove it to "
                    + "turn automatic issue cleanup off (docs/operate/configuration.md).");
        }
        if (parsed.compareTo(SHORTEST) < 0) {
            throw stop(KEY + "=" + value + " is shorter than P90D, the shortest period taken. popdesk did not start. "
                    + "Set P90D or longer, or remove it to turn automatic issue cleanup off "
                    + "(docs/operate/configuration.md).");
        }
        period = parsed;
        log.info("Automatic issue cleanup moves CLOSED issues to the trash {} days after they were closed ({}).",
                parsed.toDays(), KEY);
    }

    /** The period; empty while the cleanup is off. */
    Optional<Duration> period() {
        return Optional.ofNullable(period);
    }

    private static IllegalStateException stop(String message) {
        log.error(message);
        return new IllegalStateException(message);
    }
}
