package io.popdesk;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who sent a request and to which host, after {@code X-Forwarded-*} from a trusted proxy is applied
 * (S0-AC-18). {@link ForwardedHeadersFilter} puts it on every request; read it with {@link #of}.
 *
 * @param clientIp the client's address: from {@code X-Forwarded-For} when a trusted proxy sent it,
 *     else the connection's address. Kept in memory only (PII-1)
 * @param host the host as received, port included: {@code X-Forwarded-Host} from a trusted proxy, else
 *     {@code Host}
 * @param hostName {@link #host} without its port, for comparing with the configured hosts
 */
public record Forwarded(String clientIp, String host, String hostName) {

    static final String ATTRIBUTE = Forwarded.class.getName();

    /** The request's forwarded view; the filter has set it on every request through the app. */
    public static Forwarded of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof Forwarded forwarded) {
            return forwarded;
        }
        String host = request.getHeader("Host");
        return new Forwarded(request.getRemoteAddr(), host, withoutPort(host));
    }

    /** {@code name} of {@code name:port}, {@code [v6]:port}, or a name without a port. */
    static String withoutPort(String host) {
        if (host == null) {
            return null;
        }
        String h = host.strip();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? h : h.substring(0, end + 1);
        }
        int colon = h.lastIndexOf(':');
        return colon < 0 || h.indexOf(':') != colon ? h : h.substring(0, colon);
    }

    /** The port of {@code name:port} or {@code [v6]:port}, or null when there is none. */
    static String portOf(String host) {
        if (host == null) {
            return null;
        }
        String h = host.strip();
        int start = h.startsWith("[") ? h.indexOf(']') + 1 : 0;
        int colon = h.indexOf(':', start);
        if (colon < 0 || (start == 0 && h.indexOf(':') != h.lastIndexOf(':'))) {
            return null;
        }
        return h.substring(colon + 1);
    }
}
