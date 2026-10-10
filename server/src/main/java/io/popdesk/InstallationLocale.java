package io.popdesk;

import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The installation's language, {@code POPDESK_LOCALE} (docs/operate/configuration.md): {@code en} or
 * {@code ko}, read here and nowhere else. Spaces around the value and its case do not matter; without
 * the key it is {@code en}. An empty value or any other value, such as {@code ko-KR}, is not a
 * language popdesk has texts for: the app then takes no requests until it is set right (the first
 * admin step refuses to finish, as for a missing first admin).
 */
@Component
public class InstallationLocale {

    public static final String KEY = "POPDESK_LOCALE";

    /** The language without the key, and the one texts fall back to while the value is not valid. */
    public static final String DEFAULT = "en";

    private final String language;

    InstallationLocale(Environment environment) {
        this.language = parse(environment.getProperty(KEY)).orElse(null);
    }

    /** {@code en} or {@code ko} for a value popdesk takes, {@code en} for no value at all; empty otherwise. */
    static Optional<String> parse(String configured) {
        if (configured == null) {
            return Optional.of(DEFAULT);
        }
        String value = configured.strip().toLowerCase(Locale.ROOT);
        return value.equals("en") || value.equals("ko") ? Optional.of(value) : Optional.empty();
    }

    /** Whether {@code POPDESK_LOCALE} is a value popdesk takes. */
    public boolean valid() {
        return language != null;
    }

    /** {@code en} or {@code ko}. While the value is not valid, {@code en}; no request is served then. */
    public String language() {
        return language == null ? DEFAULT : language;
    }
}
