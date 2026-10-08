package io.popdesk.support;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * A response from the app: status, headers, and the body as bytes, text, or JSON. Everything that
 * looks into the body fails with an AssertionError when the body is not what it should be, so a
 * check on a 501 placeholder fails as an assertion.
 */
public final class ApiResponse {

    private final String request;
    private final HttpResponse<byte[]> response;

    ApiResponse(String request, HttpResponse<byte[]> response) {
        this.request = request;
        this.response = response;
    }

    /** The request this answers, for messages: {@code POST /api/x on portal.popdesk.test}. */
    public String request() {
        return request;
    }

    public int status() {
        return response.statusCode();
    }

    /** The first value of a header, or null. Names ignore case. */
    public String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    public List<String> headers(String name) {
        return response.headers().allValues(name);
    }

    public Map<String, List<String>> headers() {
        return response.headers().map();
    }

    /** The {@code Set-Cookie} header that sets {@code name}, with all its attributes, or null. */
    public String setCookie(String name) {
        return headers("Set-Cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .findFirst()
                .orElse(null);
    }

    public byte[] body() {
        return response.body() == null ? new byte[0] : response.body();
    }

    public String text() {
        return new String(body(), StandardCharsets.UTF_8);
    }

    /** The body as JSON. Fails with an AssertionError when it is not JSON. */
    public JsonNode json() {
        try {
            return ApiRequest.JSON.readTree(body());
        } catch (JacksonException e) {
            throw new AssertionError(request + " answered " + status() + " with a body that is not JSON: "
                    + snippet(), e);
        }
    }

    /** The body read into a type (a record of your own). Fails with an AssertionError when it does not fit. */
    public <T> T as(Class<T> type) {
        try {
            return ApiRequest.JSON.readValue(body(), type);
        } catch (JacksonException e) {
            throw new AssertionError(request + " answered " + status() + " with a body that is not a "
                    + type.getSimpleName() + ": " + snippet(), e);
        }
    }

    /** A text field of the JSON body. Fails with an AssertionError when it is missing or not text. */
    public String jsonText(String field) {
        JsonNode node = json().path(field);
        if (!node.isString()) {
            throw new AssertionError(request + ": no text field '" + field + "' in " + snippet());
        }
        return node.stringValue();
    }

    /** A number field of the JSON body. Fails with an AssertionError when it is missing or not a number. */
    public long jsonLong(String field) {
        JsonNode node = json().path(field);
        if (!node.isIntegralNumber()) {
            throw new AssertionError(request + ": no number field '" + field + "' in " + snippet());
        }
        return node.asLong();
    }

    /** The {@code code} of an error body (conventions COM-3). */
    public String errorCode() {
        return jsonText("code");
    }

    /** sha256 of the body, as lower-case hex. */
    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fails with an AssertionError, showing the request and the body, unless the status is {@code expected}. */
    public ApiResponse expectStatus(int expected) {
        if (status() != expected) {
            throw new AssertionError("Expected " + expected + " from " + request + " but got " + status()
                    + ": " + snippet());
        }
        return this;
    }

    /** The start of the body, for failure messages. */
    public String snippet() {
        String text = text();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    @Override
    public String toString() {
        return request + " -> " + status();
    }
}
