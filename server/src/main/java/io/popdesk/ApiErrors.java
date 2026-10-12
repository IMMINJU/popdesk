package io.popdesk;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The error codes and default messages of conventions "Error codes", and the one way to write an
 * error body outside Spring MVC (from a filter). Messages never name an input value, a key, or an ID.
 */
public final class ApiErrors {

    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String INVALID_INPUT_MESSAGE = "Some fields are invalid.";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String UNAUTHENTICATED_MESSAGE = "Sign in to continue.";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String FORBIDDEN_MESSAGE = "You may not do this.";
    public static final String CSRF_TOKEN_INVALID = "CSRF_TOKEN_INVALID";
    public static final String CSRF_TOKEN_INVALID_MESSAGE = "The request has no valid CSRF token. Reload the page.";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_FOUND_MESSAGE = "Not found.";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String RATE_LIMITED_MESSAGE = "Too many requests. Try again later.";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String PAYLOAD_TOO_LARGE_MESSAGE = "The request is too large.";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String INTERNAL_ERROR_MESSAGE = "Something went wrong on the server. Try again later.";
    public static final String SERVER_BUSY = "SERVER_BUSY";
    public static final String SERVER_BUSY_MESSAGE = "The server is busy. Try again later.";
    public static final String CHANNEL_DISABLED = "CHANNEL_DISABLED";
    public static final String CHANNEL_DISABLED_MESSAGE = "This channel is not set up, or the sandbox turned it off.";
    public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    public static final String NOT_IMPLEMENTED_MESSAGE = "Not built yet.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApiErrors() {}

    /** Writes {@code body} as the response with {@code status}. */
    public static void write(HttpServletResponse response, int status, ApiError body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }

    public static void write(HttpServletResponse response, ApiException e) throws IOException {
        write(response, e.status().value(), e.body());
    }
}
