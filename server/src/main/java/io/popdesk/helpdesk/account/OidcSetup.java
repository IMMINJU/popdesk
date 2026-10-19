package io.popdesk.helpdesk.account;

import io.popdesk.PublicAddress.App;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The OIDC settings of this start ({@link OidcSettings}, S11-AC-01, S11-AC-02): read once, checked before the app
 * takes requests, and never the provider's business. Wrong settings stop the start with one log line per problem,
 * naming the key and never a value; right ones start without reaching for the provider. An {@code http} issuer gets
 * a warning, as an {@code http} webhook address does (D-51).
 */
@Component
class OidcSetup implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OidcSetup.class);

    private final OidcSettings settings;

    OidcSetup(Environment environment) {
        this.settings = OidcSettings.read(environment::getProperty);
    }

    @Override
    public void afterPropertiesSet() {
        if (!settings.problems().isEmpty()) {
            for (String problem : settings.problems()) {
                log.error("{} popdesk did not start (docs/operate/configuration.md, \"OIDC sign-in\").", problem);
            }
            throw new IllegalStateException("The OIDC settings are wrong: " + String.join(" ", settings.problems()));
        }
        if (!settings.enabled()) {
            log.info("OIDC sign-in is off ({} is not set).", OidcSettings.ISSUER);
            return;
        }
        log.info("OIDC sign-in is on for the {}.", settings.on(App.CONSOLE) && settings.on(App.PORTAL)
                ? "console and the portal" : settings.on(App.CONSOLE) ? "console" : "portal");
        if (settings.httpIssuer()) {
            log.warn("{} uses http, so the sign-in's code and ID token travel without encryption between popdesk and "
                    + "the provider. Use https when the provider is outside your network.", OidcSettings.ISSUER);
        }
    }

    OidcSettings settings() {
        return settings;
    }

    /** Whether OIDC sign-in is on for {@code app} (S11-AC-03, S11-AC-06). */
    boolean on(App app) {
        return settings.on(app);
    }
}
