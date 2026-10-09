package io.popdesk.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import javax.net.ssl.SSLContext;

import tools.jackson.databind.JsonNode;

/**
 * One person using one app over HTTP, with their own cookies: the session cookie and the CSRF
 * cookie, which the server keeps host-only. Writes send the CSRF cookie back in {@code X-XSRF-TOKEN}
 * (conventions.md "Sessions and CSRF").
 */
public final class Actor {

    private final String name;
    private final URI base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    /** {@code base} is scheme, host, and port, for example {@code https://portal.e2e.test:4430}. */
    public Actor(String name, String base, SSLContext ssl) {
        this.name = name;
        this.base = URI.create(base);
        // HTTP/1.1 like most proxies speak to the app; the JDK default would send h2c upgrade headers on http.
        HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10));
        if (ssl != null) {
            b.sslContext(ssl);
        }
        this.http = b.build();
    }

    public URI base() {
        return base;
    }

    public HttpResponse<String> get(String path) {
        return send(request(path).GET(), BodyHandlers.ofString());
    }

    /** GET on a full URL, for opening links as they are. The fragment stays in the browser, so it is left out. */
    public HttpResponse<String> open(URI link) {
        URI noFragment = URI.create(link.toString().split("#", 2)[0]);
        return send(HttpRequest.newBuilder(noFragment).timeout(Duration.ofSeconds(60)).GET(), BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, Object body) {
        return write("POST", path, "application/json", BodyPublishers.ofString(Json.write(body)));
    }

    public HttpResponse<String> put(String path) {
        return write("PUT", path, null, BodyPublishers.noBody());
    }

    public HttpResponse<String> post(String path) {
        return write("POST", path, null, BodyPublishers.noBody());
    }

    public HttpResponse<String> postMultipart(String path, Multipart body) {
        return write("POST", path, body.contentType(), body.publisher());
    }

    /** Downloads a file and returns its status and the sha256 of its content, without holding it in memory. */
    public Download download(String path) {
        HttpResponse<InputStream> r = send(request(path).GET(), BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            long size = 0;
            for (int n; (n = in.read(buf)) > 0; size += n) {
                sha.update(buf, 0, n);
            }
            return new Download(r.statusCode(), r.headers().map(), size, HexFormat.of().formatHex(sha.digest()));
        } catch (IOException e) {
            throw new AssertionError(name + " could not read the download of " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Download(int status, Map<String, java.util.List<String>> headers, long size, String sha256) {}

    public void signIn(String email, String password) {
        HttpResponse<String> r = postJson("/api/public/sign-in", Map.of("email", email, "password", password));
        expect(r, 204);
    }

    /** Accepts an invitation; the server signs the person in on success (S0-AC-36). */
    public void accept(String token, String password) {
        HttpResponse<String> r = postJson("/api/public/invitations/accept", Map.of("token", token, "password", password));
        expect(r, 204);
    }

    /** Asserts the status and returns the JSON body. */
    public JsonNode json(HttpResponse<String> r, int status) {
        expect(r, status);
        assertThat(r.headers().firstValue("Content-Type").orElse(""))
                .as("%s: Content-Type of %s %s", name, r.request().method(), r.uri()).startsWith("application/json");
        return Json.parse(r.body());
    }

    public void expect(HttpResponse<?> r, int status) {
        assertThat(r.statusCode()).as("%s: %s %s answered %d: %s", name, r.request().method(), r.uri(), r.statusCode(),
                r.body() instanceof String s ? s : "").isEqualTo(status);
    }

    private HttpResponse<String> write(String method, String path, String contentType, BodyPublisher body) {
        String token = csrfToken();
        HttpRequest.Builder b = request(path).method(method, body).header("X-XSRF-TOKEN", token);
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return send(b, BodyHandlers.ofString());
    }

    /** Fetches the CSRF cookie before each write, so a token the server rotated at sign-in is picked up. */
    private String csrfToken() {
        HttpResponse<String> r = get("/api/public/csrf");
        expect(r, 204);
        return cookies.getCookieStore().get(base).stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst()
                .orElseThrow(() -> new AssertionError(name + ": GET /api/public/csrf on " + base + " set no XSRF-TOKEN cookie"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120));
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler) {
        HttpRequest req = b.build();
        try {
            return http.send(req, handler);
        } catch (IOException e) {
            throw new AssertionError(name + ": " + req.method() + " " + req.uri() + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
