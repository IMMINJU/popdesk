package io.popdesk;

import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * An expected failure that becomes an error response (conventions "Error response"): the status, the
 * stable {@code code}, and for 400 {@code INVALID_INPUT} the invalid fields. The message is the
 * default text the client shows for a code it does not know; it never holds input values or IDs.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fields;
    private List<String> issueKeys;
    private Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fields) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.fields = fields == null ? null : List.copyOf(fields);
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Seconds for the {@code Retry-After} header of a 429 or 503, or null. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ApiError body() {
        return new ApiError(code, getMessage(), fields, issueKeys);
    }

    /** 404: the target does not exist or the caller may not see it; one body for both (COM-1). */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrors.NOT_FOUND, ApiErrors.NOT_FOUND_MESSAGE);
    }

    /** 403: the caller may not do this (COM-2). */
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, ApiErrors.FORBIDDEN, ApiErrors.FORBIDDEN_MESSAGE);
    }

    /** 400 {@code INVALID_INPUT} listing every invalid field (D-44). */
    public static ApiException invalid(List<ApiError.FieldError> fields) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.INVALID_INPUT, ApiErrors.INVALID_INPUT_MESSAGE,
                fields);
    }

    /** 400 {@code INVALID_INPUT} for one field. */
    public static ApiException invalid(String field, String fieldCode) {
        return invalid(List.of(new ApiError.FieldError(field, fieldCode)));
    }

    /**
     * 429 {@code RATE_LIMITED} (COM-8). {@code Retry-After} is the time left until the caller may try
     * again, in whole seconds rounded up and at least 1, so a request sent after it is not refused
     * (S1A-AC-71).
     */
    public static ApiException rateLimited(Duration left) {
        ApiException e = new ApiException(HttpStatus.TOO_MANY_REQUESTS, ApiErrors.RATE_LIMITED,
                ApiErrors.RATE_LIMITED_MESSAGE);
        e.retryAfterSeconds = Math.max(1, left.plusNanos(999_999_999).toSeconds());
        return e;
    }

    /**
     * 503 {@code SERVER_BUSY}: the server already does as much of this as it does at once; the caller
     * tries again after {@code seconds}.
     */
    public static ApiException busy(long seconds) {
        ApiException e = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrors.SERVER_BUSY,
                ApiErrors.SERVER_BUSY_MESSAGE);
        e.retryAfterSeconds = seconds;
        return e;
    }

    /** 400 {@code CHANNEL_DISABLED}: the outgoing channel is off, so nothing was sent or queued (S4B-AC-17). */
    public static ApiException channelDisabled() {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.CHANNEL_DISABLED,
                ApiErrors.CHANNEL_DISABLED_MESSAGE);
    }

    /**
     * 409 {@code RESTORE_LINK_CONFLICT}: with its links the issue would break the link rules (S7A-AC-24).
     *
     * @param issueKeys the keys of the other issues of the links at fault, at least one
     */
    public static ApiException restoreLinkConflict(List<String> issueKeys) {
        ApiException e = conflict("RESTORE_LINK_CONFLICT",
                "With its links this issue would break the link rules, so it was not restored.");
        e.issueKeys = List.copyOf(issueKeys);
        return e;
    }

    /** 409 for a state that does not allow the change. */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
