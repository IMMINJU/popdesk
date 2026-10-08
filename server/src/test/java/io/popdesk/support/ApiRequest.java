package io.popdesk.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** One request of an {@link ApiClient}, built step by step and sent with {@link #send()}. */
public final class ApiRequest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ApiClient client;
    private final String method;
    private final String path;
    private final StringBuilder query = new StringBuilder();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body;
    private String contentType;
    private List<Part> parts;
    private boolean csrf;
    private String csrfToken;

    ApiRequest(ApiClient client, String method, String path) {
        this.client = client;
        this.method = method;
        this.path = path;
        this.csrf = WRITES.contains(method);
    }

    /** Adds a header. A {@code Host} header here replaces the client's host for this request. */
    public ApiRequest header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    /** Adds a query parameter; name and value are URL-encoded. */
    public ApiRequest query(String name, Object value) {
        query.append(query.isEmpty() ? '?' : '&')
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        return this;
    }

    /** A JSON body: a Map, a record, or a list, written with Jackson. */
    public ApiRequest json(Object value) {
        return body(JSON.writeValueAsBytes(value), "application/json");
    }

    /** A JSON body written as is, for input that a Map cannot express (bad JSON, duplicate keys). */
    public ApiRequest jsonText(String json) {
        return body(json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    public ApiRequest body(byte[] bytes, String contentType) {
        this.body = bytes;
        this.contentType = contentType;
        this.parts = null;
        return this;
    }

    /** Adds a text field to a {@code multipart/form-data} body. */
    public ApiRequest part(String name, String value) {
        return addPart(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Adds a JSON field with {@code Content-Type: application/json} to a multipart body. */
    public ApiRequest jsonPart(String name, Object value) {
        return addPart(new Part(name, null, "application/json", JSON.writeValueAsBytes(value)));
    }

    /** Adds a file to a multipart body. */
    public ApiRequest file(String name, String fileName, String contentType, byte[] content) {
        return addPart(new Part(name, fileName, contentType, content));
    }

    /** Sends this request without a CSRF header, even if it changes data. */
    public ApiRequest withoutCsrf() {
        this.csrf = false;
        this.csrfToken = null;
        return this;
    }

    /** Sends this CSRF header value instead of the client's token. */
    public ApiRequest csrfToken(String token) {
        this.csrf = true;
        this.csrfToken = token;
        return this;
    }

    /** Sends the request and returns the response, whatever its status. */
    public ApiResponse send() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(client.base() + path + query))
                .timeout(Duration.ofSeconds(60));
        boolean hostGiven = headers.keySet().stream().anyMatch("Host"::equalsIgnoreCase);
        if (!hostGiven) {
            builder.header("Host", client.host());
        }
        headers.forEach((name, values) -> values.forEach(v -> builder.header(name, v)));
        if (csrf) {
            builder.header(ApiClient.CSRF_HEADER, csrfToken != null ? csrfToken : client.csrfToken());
        }
        String cookies = client.cookieHeader();
        if (cookies != null) {
            builder.header("Cookie", cookies);
        }

        byte[] payload = body;
        String type = contentType;
        if (parts != null) {
            String boundary = "popdesk-" + UUID.randomUUID();
            payload = multipart(boundary);
            type = "multipart/form-data; boundary=" + boundary;
        }
        if (type != null && headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            builder.header("Content-Type", type);
        }
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload));

        HttpResponse<byte[]> response;
        try {
            response = client.http().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new NoResponse(method + " " + path + " on " + client.host() + " got no response: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(method + " " + path + " was interrupted", e);
        }
        client.storeCookies(response.headers().allValues("Set-Cookie"));
        return new ApiResponse(method + " " + path + query + " on " + client.host(), response);
    }

    /** Sends the request and fails with an AssertionError unless the status is {@code status}. */
    public ApiResponse expect(int status) {
        return send().expectStatus(status);
    }

    private ApiRequest addPart(Part part) {
        if (parts == null) {
            parts = new ArrayList<>();
            body = null;
            contentType = null;
        }
        parts.add(part);
        return this;
    }

    private byte[] multipart(String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
            StringBuilder head = new StringBuilder()
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(quoted(part.name())).append('"');
            if (part.fileName() != null) {
                head.append("; filename=\"").append(quoted(part.fileName())).append('"');
            }
            head.append("\r\n");
            if (part.contentType() != null) {
                head.append("Content-Type: ").append(part.contentType()).append("\r\n");
            }
            head.append("\r\n");
            out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(part.content());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String quoted(String value) {
        return value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    /**
     * The request got no HTTP response at all (connection refused, reset, timed out). An
     * AssertionError, so a red run reads it as a failed check; a test that expects a refused
     * connection can catch this type.
     */
    public static final class NoResponse extends AssertionError {

        NoResponse(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Part(String name, String fileName, String contentType, byte[] content) {}
}
