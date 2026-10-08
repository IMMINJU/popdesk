package io.popdesk.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An HTTP client that talks to the app as one browser on one host: it sends {@code Host: <host>} on
 * every request and keeps its own cookies, so a portal client and a console client never share a
 * session, like host-only cookies in a browser.
 *
 * <p>Requests that change data (POST, PUT, PATCH, DELETE) carry the CSRF token in
 * {@value #CSRF_HEADER}. The token is the {@value #CSRF_COOKIE} cookie; when the client has none, it
 * first calls {@code GET /api/public/csrf}. {@link ApiRequest#withoutCsrf()} and
 * {@link ApiRequest#csrfToken(String)} turn this off for one request.
 *
 * <p>Connections always go to 127.0.0.1 on the app's port; only the {@code Host} header changes. The
 * header is sent without the port, as a reverse proxy in front of the app would pass it on.
 *
 * <p>Each client also sends {@code X-Forwarded-For} with an address of its own from 198.18.0.0/15
 * (reserved for testing). Loopback is a trusted proxy by default, so the app counts each client as a
 * different IP and the sign-in limit per IP does not catch fixtures that sign in many
 * times. {@link #forwardedFor(String)} picks the address, {@link #withoutForwardedFor()} sends none,
 * and an {@code X-Forwarded-For} header on one request replaces it for that request.
 */
public final class ApiClient {

    /** Spring Security's cookie CSRF names, which the browser apps read and send back. */
    public static final String CSRF_COOKIE = "XSRF-TOKEN";

    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    public static final String CSRF_PATH = "/api/public/csrf";

    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    static {
        JdkHttp.allowHostHeader();
    }

    private final String host;
    private final URI base;
    private final HttpClient http;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private volatile String forwardedFor = nextAddress();

    public ApiClient(String host, int port) {
        this.host = host;
        this.base = URI.create("http://127.0.0.1:" + port);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The value of the {@code Host} header this client sends. */
    public String host() {
        return host;
    }

    public int port() {
        return base.getPort();
    }

    /** The {@code X-Forwarded-For} this client sends, or null when it sends none. */
    public String forwardedFor() {
        return forwardedFor;
    }

    /** Sends {@code X-Forwarded-For: ip} from now on: this client is a browser at that address. */
    public ApiClient forwardedFor(String ip) {
        this.forwardedFor = ip;
        return this;
    }

    /** Sends no {@code X-Forwarded-For} from now on: the app sees the request come from 127.0.0.1. */
    public ApiClient withoutForwardedFor() {
        this.forwardedFor = null;
        return this;
    }

    public ApiRequest get(String path) {
        return request("GET", path);
    }

    public ApiRequest head(String path) {
        return request("HEAD", path);
    }

    public ApiRequest post(String path) {
        return request("POST", path);
    }

    public ApiRequest put(String path) {
        return request("PUT", path);
    }

    public ApiRequest patch(String path) {
        return request("PATCH", path);
    }

    public ApiRequest delete(String path) {
        return request("DELETE", path);
    }

    public ApiRequest request(String method, String path) {
        return new ApiRequest(this, method, path);
    }

    /** Cookies this client holds now, by name. */
    public synchronized Map<String, String> cookies() {
        return Map.copyOf(cookies);
    }

    public synchronized Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    public synchronized void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public synchronized void removeCookie(String name) {
        cookies.remove(name);
    }

    /** Forgets every cookie: the session and the CSRF token. */
    public synchronized void clearCookies() {
        cookies.clear();
    }

    /**
     * The CSRF token, fetched with {@code GET /api/public/csrf} when the client has none. Fails with
     * an AssertionError when that call does not answer 204 with the cookie.
     */
    public String csrfToken() {
        Optional<String> held = cookie(CSRF_COOKIE);
        if (held.isPresent()) {
            return held.get();
        }
        ApiResponse response = get(CSRF_PATH).send();
        response.expectStatus(204);
        return cookie(CSRF_COOKIE).orElseThrow(() -> new AssertionError(
                "GET " + CSRF_PATH + " on " + host + " set no " + CSRF_COOKIE + " cookie. Set-Cookie: "
                        + response.headers("Set-Cookie")));
    }

    /** The next unused address in 198.18.0.0/15, skipping .0 and .255. */
    private static String nextAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        int host = n % 254 + 1;
        int net = n / 254;
        if (net >= 512) {
            throw new IllegalStateException("More than 130,000 clients in one JVM");
        }
        return "198." + (18 + net / 256) + "." + (net % 256) + "." + host;
    }

    URI base() {
        return base;
    }

    HttpClient http() {
        return http;
    }

    synchronized String cookieHeader() {
        if (cookies.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(name).append('=').append(value);
        });
        return out.toString();
    }

    /** Takes the cookies a response set, and drops the ones it expired. */
    synchronized void storeCookies(List<String> setCookieHeaders) {
        for (String header : setCookieHeaders) {
            String[] parts = header.split(";");
            int eq = parts[0].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = parts[0].substring(0, eq).strip();
            String value = parts[0].substring(eq + 1).strip();
            if (expired(parts)) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    private static boolean expired(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].strip();
            int eq = attribute.indexOf('=');
            String key = (eq < 0 ? attribute : attribute.substring(0, eq)).strip();
            String value = eq < 0 ? "" : attribute.substring(eq + 1).strip();
            if (key.equalsIgnoreCase("Max-Age")) {
                try {
                    return Long.parseLong(value) <= 0;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            if (key.equalsIgnoreCase("Expires")) {
                try {
                    return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                            .isBefore(Instant.now());
                } catch (DateTimeParseException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ApiClient[" + host + "]";
    }
}
