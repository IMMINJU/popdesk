package io.popdesk.deploy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Plain HTTP GETs to things that are not the app's API: the management port, the Mailpit and
 * GreenMail APIs, Grafana. A failure to connect or a status other than 200 is an
 * {@link AssertionError}.
 */
final class Http {

    record Response(int status, String body) {}

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    private Http() {}

    static String get(String url) {
        Response r = send(url, null);
        if (r.status() != 200) {
            throw new AssertionError("GET " + url + " answered " + r.status() + ": " + Stack.tail(r.body()));
        }
        return r.body();
    }

    /** A GET with HTTP basic authentication (Grafana). */
    static Response get(String url, String user, String password) {
        return send(url, "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    /** A GET that returns null when nothing answers yet, for use inside a wait. */
    static Response attempt(String url, String user, String password) {
        try {
            return user == null ? send(url, null) : get(url, user, password);
        } catch (AssertionError notYet) {
            return null;
        }
    }

    static Response send(String url, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new AssertionError("GET " + url + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted: GET " + url, e);
        }
    }
}
