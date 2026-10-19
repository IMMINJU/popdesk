package io.popdesk.helpdesk.account;

import io.popdesk.PublicAddress.App;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The OIDC settings (AUTH-6, S11-AC-01): {@code OIDC_ISSUER}, {@code OIDC_CLIENT_ID}, {@code OIDC_CLIENT_SECRET}
 * (also as {@code OIDC_CLIENT_SECRET_FILE}, which {@code SecretFiles} has turned into the value), and
 * {@code OIDC_APPS}. Reading them is pure: the values in, the apps OIDC is on for or the problems out. A key that is
 * not set and one set to blank text are the same. No problem holds a value, so none holds the client secret.
 */
final class OidcSettings {

    static final String ISSUER = "OIDC_ISSUER";
    static final String CLIENT_ID = "OIDC_CLIENT_ID";
    static final String CLIENT_SECRET = "OIDC_CLIENT_SECRET";
    static final String APPS = "OIDC_APPS";

    /** The path the discovery document is read from, after the issuer without its last {@code /}. */
    static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private final List<String> problems;
    private final Set<App> apps;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;

    private OidcSettings(List<String> problems, Set<App> apps, String issuer, String clientId, String clientSecret) {
        this.problems = List.copyOf(problems);
        this.apps = apps.isEmpty() ? Set.of() : EnumSet.copyOf(apps);
        this.issuer = issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Reads the four keys.
     *
     * <ul>
     *   <li>None of the issuer, client ID, and client secret: OIDC is off, with no problem.</li>
     *   <li>Some of them but not all: one problem per missing key, naming it.</li>
     *   <li>{@code OIDC_APPS} not set: {@code console}. Otherwise {@code console}, {@code portal}, or {@code both},
     *       in any letter case and with spaces around it; any other value is a problem, also while OIDC is off.</li>
     *   <li>{@code OIDC_ISSUER} must be an absolute {@code http} or {@code https} address with a host and without
     *       a query or a fragment; it is kept as written (spaces around it cut), with a last {@code /} if it has
     *       one, since ID tokens and the discovery document are compared with it as it is.</li>
     * </ul>
     *
     * @param setting the value of a key, or null when it is not set; never called with other keys than the four
     * @return the settings; {@link #problems} is empty when the app may start with them
     */
    static OidcSettings read(Function<String, String> setting) {
        String issuer = value(setting, ISSUER);
        String clientId = value(setting, CLIENT_ID);
        String rawSecret = setting.apply(CLIENT_SECRET);
        String clientSecret = rawSecret == null || rawSecret.isBlank() ? null : rawSecret;
        String apps = value(setting, APPS);
        List<String> problems = new ArrayList<>();

        Set<App> chosen = EnumSet.noneOf(App.class);
        String appsName = apps == null ? "console" : apps.toLowerCase(Locale.ROOT);
        switch (appsName) {
            case "console" -> chosen.add(App.CONSOLE);
            case "portal" -> chosen.add(App.PORTAL);
            case "both" -> {
                chosen.add(App.CONSOLE);
                chosen.add(App.PORTAL);
            }
            default -> problems.add(APPS + " must be console, portal, or both.");
        }

        boolean any = issuer != null || clientId != null || clientSecret != null;
        if (!any) {
            return new OidcSettings(problems, Set.of(), null, null, null);
        }
        boolean all = issuer != null && clientId != null && clientSecret != null;
        if (!all) {
            for (String key : List.of(ISSUER, CLIENT_ID, CLIENT_SECRET)) {
                if (setting.apply(key) == null || setting.apply(key).isBlank()) {
                    problems.add(key + " is not set, while other OIDC keys are. Set " + ISSUER + ", " + CLIENT_ID
                            + ", and " + CLIENT_SECRET + " (or " + CLIENT_SECRET + "_FILE) together, or none of them.");
                }
            }
        }
        if (issuer != null) {
            issuerProblem(issuer).ifPresent(problems::add);
        }
        if (!problems.isEmpty()) {
            return new OidcSettings(problems, Set.of(), null, null, null);
        }
        return new OidcSettings(problems, chosen, issuer, clientId, clientSecret);
    }

    private static Optional<String> issuerProblem(String issuer) {
        String why = null;
        try {
            URI uri = new URI(issuer);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))) {
                why = "is not an absolute http or https address";
            } else if (uri.getHost() == null || uri.getHost().isEmpty()) {
                why = "has no host";
            } else if (uri.getRawQuery() != null) {
                why = "has a query";
            } else if (uri.getRawFragment() != null) {
                why = "has a fragment";
            }
        } catch (URISyntaxException e) {
            why = "is not an address";
        }
        return why == null ? Optional.empty()
                : Optional.of(ISSUER + " " + why + ". It must be the provider's issuer, an absolute http "
                        + "or https address without a query or a fragment, as in its discovery document.");
    }

    private static String value(Function<String, String> setting, String key) {
        String value = setting.apply(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** What stops the start, each naming its key and never a value; empty when nothing does. */
    List<String> problems() {
        return problems;
    }

    /** Whether OIDC sign-in is on for at least one app. */
    boolean enabled() {
        return !apps.isEmpty();
    }

    /** Whether OIDC sign-in is on for {@code app}. */
    boolean on(App app) {
        return apps.contains(app);
    }

    /** The apps OIDC sign-in is on for, {@code CONSOLE} before {@code PORTAL}; empty when off. */
    List<App> apps() {
        return List.copyOf(apps);
    }

    /** {@code OIDC_ISSUER} as written; null when off. */
    String issuer() {
        return issuer;
    }

    String clientId() {
        return clientId;
    }

    /** Never logged. */
    String clientSecret() {
        return clientSecret;
    }

    /** Whether the issuer is an {@code http} address (S11-AC-02 warns at startup). False when off. */
    boolean httpIssuer() {
        return issuer != null && issuer.toLowerCase(Locale.ROOT).startsWith("http:");
    }

    /** Whether the issuer is an {@code https} address: then every endpoint must be {@code https} (S11-AC-07). */
    boolean httpsIssuer() {
        return issuer != null && issuer.toLowerCase(Locale.ROOT).startsWith("https:");
    }

    /**
     * {@code {issuer}/.well-known/openid-configuration} with one last {@code /} of the issuer cut, so
     * {@code https://id.example/} and {@code https://id.example} read the same address; null when off.
     */
    URI discoveryUri() {
        if (issuer == null) {
            return null;
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return URI.create(base + DISCOVERY_PATH);
    }

    @Override
    public String toString() {
        return "OidcSettings[apps=" + apps + ", problems=" + problems.size() + "]";
    }
}
