package io.popdesk;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Forwarded-For}, {@code -Host}, {@code -Proto}, and {@code -Port}, but only from a
 * trusted proxy (S0-AC-18); from any other address they are ignored. Sets {@link Forwarded} on the
 * request. When a trusted proxy forwards a scheme or port other than the public ones, logs one
 * warning per (scheme, port) pair (S0-AC-19): links in emails would not match what the proxy serves.
 *
 * <p>Not a bean: the security filter chain runs it, so its responses carry the security headers.
 */
public class ForwardedHeadersFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ForwardedHeadersFilter.class);

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]{0,15}");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");
    private static final int MAX_WARNED_PAIRS = 1000;

    private final TrustedProxies trusted;
    private final PublicAddress address;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ForwardedHeadersFilter(String trustedProxies, PublicAddress address) {
        this.trusted = TrustedProxies.parse(trustedProxies);
        this.address = address;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String remote = request.getRemoteAddr();
        String host = request.getHeader("Host");
        String clientIp = remote;
        if (trusted.trusts(remote)) {
            clientIp = client(request.getHeader("X-Forwarded-For"), remote);
            String forwardedHost = first(request.getHeader("X-Forwarded-Host"));
            if (forwardedHost != null) {
                host = forwardedHost;
            }
            warnOnMismatch(first(request.getHeader("X-Forwarded-Proto")), first(request.getHeader("X-Forwarded-Port")),
                    forwardedHost == null ? null : Forwarded.portOf(forwardedHost));
        }
        request.setAttribute(Forwarded.ATTRIBUTE, new Forwarded(clientIp, host, Forwarded.withoutPort(host)));
        chain.doFilter(request, response);
    }

    /** The rightmost address that is not a trusted proxy; the leftmost when all are trusted. */
    private String client(String forwardedFor, String remote) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remote;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!hop.isEmpty() && !trusted.trusts(hop)) {
                return hop;
            }
        }
        String leftmost = hops[0].strip();
        return leftmost.isEmpty() ? remote : leftmost;
    }

    private void warnOnMismatch(String proto, String port, String hostPort) {
        if (proto == null && port == null && hostPort == null) {
            return;
        }
        String scheme = proto == null ? address.scheme() : proto.toLowerCase(Locale.ROOT);
        String p = port != null ? port : hostPort != null ? hostPort : Integer.toString(PublicAddress.defaultPort(scheme));
        if (!SCHEME.matcher(scheme).matches() || !PORT.matcher(p).matches()) {
            return;
        }
        if (scheme.equals(address.scheme()) && Integer.parseInt(p) == address.port()) {
            return;
        }
        String pair = scheme + " " + Integer.parseInt(p);
        if (warned.size() < MAX_WARNED_PAIRS && warned.add(pair)) {
            log.warn("A trusted proxy forwarded scheme {} and port {}, but POPDESK_PUBLIC_SCHEME and POPDESK_PUBLIC_PORT "
                    + "are {} and {}. Links in emails use the configured values; set them to what the proxy serves.",
                    scheme, Integer.parseInt(p), address.scheme(), address.port());
        }
    }

    private static String first(String header) {
        if (header == null) {
            return null;
        }
        String value = header.split(",", 2)[0].strip();
        return value.isEmpty() ? null : value;
    }
}
