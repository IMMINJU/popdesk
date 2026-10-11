package io.popdesk;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The two host names and the public scheme and port (docs/operate/configuration.md), and the links
 * built from them (conventions "Invitation links", S0-AC-20). Links never come from the request: a
 * proxy may rewrite the request, and a link in an email must work from outside.
 */
@Component
public class PublicAddress {

    /** Which app a host serves. */
    public enum App {
        PORTAL,
        CONSOLE
    }

    private final String portalHost;
    private final String consoleHost;
    private final String scheme;
    private final int port;

    PublicAddress(Environment environment) {
        this.portalHost = setting(environment, "POPDESK_PORTAL_HOST", "portal.localhost").toLowerCase(Locale.ROOT);
        this.consoleHost = setting(environment, "POPDESK_CONSOLE_HOST", "console.localhost").toLowerCase(Locale.ROOT);
        this.scheme = setting(environment, "POPDESK_PUBLIC_SCHEME", "http").toLowerCase(Locale.ROOT);
        this.port = Integer.parseInt(setting(environment, "POPDESK_PUBLIC_PORT", "8080"));
    }

    private static String setting(Environment environment, String key, String fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public String portalHost() {
        return portalHost;
    }

    public String consoleHost() {
        return consoleHost;
    }

    public String scheme() {
        return scheme;
    }

    public int port() {
        return port;
    }

    /** Whether cookies get {@code Secure}: the install is reached over https (D-40). */
    public boolean secure() {
        return scheme.equals("https");
    }

    public String host(App app) {
        return app == App.PORTAL ? portalHost : consoleHost;
    }

    /**
     * The app of a host name, compared whole and without case; the caller has removed the port. Null
     * for any other host.
     */
    public App appOf(String hostName) {
        if (hostName == null) {
            return null;
        }
        String name = hostName.toLowerCase(Locale.ROOT);
        if (name.equals(portalHost)) {
            return App.PORTAL;
        }
        if (name.equals(consoleHost)) {
            return App.CONSOLE;
        }
        return null;
    }

    /** {@code scheme://host[:port]} of an app, without the port when it is the scheme's default. */
    public String origin(App app) {
        return scheme + "://" + host(app) + (port == defaultPort(scheme) ? "" : ":" + port);
    }

    /** An invitation link: the token only in the fragment, so it never reaches a request line. */
    public String invitationLink(App app, String token) {
        return origin(app) + "/invite#token=" + token;
    }

    /** A password reset link: like an invitation link, the token only in the fragment. */
    public String passwordResetLink(App app, String token) {
        return origin(app) + "/reset-password#token=" + token;
    }

    /** 443 for https, 80 for anything else. */
    public static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
