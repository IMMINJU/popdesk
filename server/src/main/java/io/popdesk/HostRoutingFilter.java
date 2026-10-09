package io.popdesk;

import io.popdesk.PublicAddress.App;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Each host serves its own app (D-43): the portal host answers 404 for {@code /api/console/**}, the
 * console host for {@code /api/portal/**} (S0-AC-13). A host that is neither gets a 404 note in plain
 * text naming both hosts and the host as received (S0-AC-14), whatever the path and {@code Accept}.
 * The note is plain text so markup in the received value is never read as HTML (S0 premortem 5).
 *
 * <p>The management port (health checks, metrics) is not a host of either app and is left alone. So
 * are springdoc's contract documents when {@code springdoc.api-docs.enabled} turns them on (the build
 * that writes docs/integrate/openapi-*.json does; a release never does): they describe both apps.
 * Not a bean: the security filter chain runs it after the header writer.
 */
public class HostRoutingFilter extends OncePerRequestFilter {

    static final String APP_ATTRIBUTE = HostRoutingFilter.class.getName() + ".app";

    private final PublicAddress address;
    private final Environment environment;
    private final String apiDocsPath;

    public HostRoutingFilter(PublicAddress address, Environment environment) {
        this.address = address;
        this.environment = environment;
        this.apiDocsPath = environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)
                ? environment.getProperty("springdoc.api-docs.path", "/v3/api-docs") : null;
    }

    /** The app of the request's host, or null on the management port. */
    public static App app(HttpServletRequest request) {
        return (App) request.getAttribute(APP_ATTRIBUTE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (onManagementPort(request) || apiDocs(request)) {
            chain.doFilter(request, response);
            return;
        }
        Forwarded forwarded = Forwarded.of(request);
        App app = address.appOf(forwarded.hostName());
        if (app == null) {
            unknownHost(response, forwarded.host());
            return;
        }
        String path = request.getRequestURI();
        if ((app == App.PORTAL && path.startsWith("/api/console/")) || (app == App.CONSOLE
                && path.startsWith("/api/portal/")) || path.equals("/api/console") || path.equals("/api/portal")) {
            ApiErrors.write(response, ApiException.notFound());
            return;
        }
        request.setAttribute(APP_ATTRIBUTE, app);
        chain.doFilter(request, response);
    }

    private boolean apiDocs(HttpServletRequest request) {
        String path = request.getRequestURI();
        return apiDocsPath != null && (path.equals(apiDocsPath) || path.startsWith(apiDocsPath + "/"));
    }

    private boolean onManagementPort(HttpServletRequest request) {
        String management = environment.getProperty("local.management.port");
        return management != null && Integer.toString(request.getLocalPort()).equals(management);
    }

    private void unknownHost(HttpServletResponse response, String received) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("text/plain; charset=utf-8");
        String note = "popdesk serves only " + address.portalHost() + " (customer portal) and " + address.consoleHost()
                + " (agent console). This request came for: " + (received == null ? "" : received) + "\n"
                + "If a proxy is in front, check that it passes the original host, and POPDESK_PORTAL_HOST and "
                + "POPDESK_CONSOLE_HOST.\n";
        response.getOutputStream().write(note.getBytes(StandardCharsets.UTF_8));
    }
}
