package io.popdesk;

import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * What an operator needs from the startup log (S0-AC-06): one line with the hosts, the public address,
 * the trusted proxies, and which features are on; and a warning for every key under popdesk's
 * prefixes that it does not know, which is usually a typo. Never a secret value.
 */
@Component
class StartLog {

    static final String DEFAULT_TRUSTED_PROXIES = "default (loopback and private ranges)";

    private static final Logger log = LoggerFactory.getLogger(StartLog.class);

    private final ConfigurableEnvironment environment;
    private final InstallationLocale locale;

    StartLog(ConfigurableEnvironment environment, InstallationLocale locale) {
        this.environment = environment;
        this.locale = locale;
    }

    @EventListener(ApplicationStartedEvent.class)
    void started() {
        for (String key : unknownKeys()) {
            log.warn("Unknown setting {}: popdesk has no setting of that name, so it is ignored. Check the spelling "
                    + "against docs/operate/configuration.md.", key);
        }
        log.info("popdesk settings: portal host {}, console host {}, public scheme {} and port {}, trusted proxies {}, "
                        + "locale {}, mail {}, webhook {}, storage {}",
                value("POPDESK_PORTAL_HOST", "portal.localhost"),
                value("POPDESK_CONSOLE_HOST", "console.localhost"),
                value("POPDESK_PUBLIC_SCHEME", "http"),
                value("POPDESK_PUBLIC_PORT", "8080"),
                value("POPDESK_TRUSTED_PROXIES", DEFAULT_TRUSTED_PROXIES),
                locale.valid() ? locale.language() : "not valid",
                on("SMTP_HOST"),
                on("NOTIFY_WEBHOOK_URL"),
                value("POPDESK_STORAGE", "db"));
    }

    private String value(String key, String fallback) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private String on(String key) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? "off" : "on";
    }

    private Set<String> unknownKeys() {
        Set<String> out = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (SettingKeys.unknown(name)) {
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }
}
