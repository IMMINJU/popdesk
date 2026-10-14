package io.popdesk;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The installation's time zone, {@code POPDESK_TIMEZONE} (docs/operate/configuration.md), read here and
 * nowhere else: it says which date "today" is for a due date (DUE-1, S6A-AC-34) and in which zone the
 * test email gives its time. The {@link Clock} stays UTC; only dates are judged in this zone.
 *
 * <p>Without the key it is UTC. An empty value or one popdesk does not take, such as {@code KST} or
 * {@code +09:00}, makes the app take no requests until it is set right (the first admin step refuses to
 * finish, as for a wrong {@code POPDESK_LOCALE}, S6A-AC-70).
 */
@Component
public class InstallationZone {

    public static final String KEY = "POPDESK_TIMEZONE";

    /** The zone without the key, and the one used while the value is not valid. Its ID is {@code UTC}. */
    public static final ZoneId DEFAULT = ZoneId.of("UTC");

    /** What the key takes, for the log line that tells an operator how to fix a wrong value. */
    public static final String TAKES = "an IANA time zone name such as Asia/Seoul, or UTC";

    private final ZoneId zone;

    InstallationZone(Environment environment) {
        this.zone = parse(environment.getProperty(KEY)).orElse(null);
    }

    /**
     * The zone of a {@code POPDESK_TIMEZONE} value (S6A-AC-71, S6A-AC-72). Pure; reads nothing else.
     *
     * @param configured the value as set, or {@code null} when the key is not set
     * @return {@link #DEFAULT} for {@code null}. Otherwise the value without the whitespace around it
     *     (as {@link String#strip()}) is looked at: exactly {@code UTC} gives {@link #DEFAULT}; a region
     *     name that holds a {@code /} and is one of {@link ZoneId#getAvailableZoneIds()}, compared with its
     *     case, gives that zone ({@code " Asia/Seoul "} is {@code Asia/Seoul}). Everything else is empty:
     *     an empty value or whitespace only, {@code utc} in other case, an offset ({@code +09:00},
     *     {@code GMT+9}, {@code Z}), a short name ({@code KST}, {@code EST}), and a name that does not
     *     exist ({@code Asia/Nowhere})
     */
    public static Optional<ZoneId> parse(String configured) {
        if (configured == null) {
            return Optional.of(DEFAULT);
        }
        String value = configured.strip();
        if (value.equals(DEFAULT.getId())) {
            return Optional.of(DEFAULT);
        }
        if (value.indexOf('/') > 0 && ZoneId.getAvailableZoneIds().contains(value)) {
            return Optional.of(ZoneId.of(value));
        }
        return Optional.empty();
    }

    /**
     * The date of the clock's instant in {@code zone}: "today" of an installation in that zone. Pure.
     *
     * @param clock the clock to read the instant from; its own zone does not matter
     * @param zone the zone the date is judged in
     * @return the local date of {@code clock.instant()} in {@code zone}, for example the next day of the
     *     UTC date at UTC 12:30 in {@code Pacific/Kiritimati} (UTC+14)
     */
    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** Whether {@code POPDESK_TIMEZONE} is a value popdesk takes. */
    public boolean valid() {
        return zone != null;
    }

    /** The installation's zone. While the value is not valid, {@link #DEFAULT}; no request is served then. */
    public ZoneId zone() {
        return zone == null ? DEFAULT : zone;
    }

    /** Today in the installation's zone, at the clock's instant. */
    public LocalDate today(Clock clock) {
        return today(clock, zone());
    }
}
