package io.popdesk;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Turns every exception from a controller into the error body (COM-3, S0-AC-85). The body and the log
 * never carry an exception's message: it can quote input, SQL, or a database value (S0 premortem 4).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return respond(e);
    }

    /** A body that is not JSON, or JSON of the wrong shape (an unknown enum value, a list for an object). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        List<ApiError.FieldError> fields = new ArrayList<>();
        if (e.getCause() instanceof DatabindException databind && !databind.getPath().isEmpty()) {
            String field = databind.getPath().getFirst().getPropertyName();
            if (field != null) {
                boolean enumValue = databind instanceof InvalidFormatException format
                        && format.getTargetType() != null && format.getTargetType().isEnum();
                fields.add(new ApiError.FieldError(field, enumValue ? "UNKNOWN_VALUE" : "INVALID_FORMAT"));
            }
        }
        return respond(fields.isEmpty() ? new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.INVALID_INPUT,
                "The request body is not valid JSON of the expected shape.") : ApiException.invalid(fields));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        boolean enumValue = e.getRequiredType() != null && e.getRequiredType().isEnum();
        return respond(ApiException.invalid(e.getName(), enumValue ? "UNKNOWN_VALUE" : "INVALID_FORMAT"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return respond(ApiException.invalid(e.getParameterName(), "REQUIRED"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return respond(new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.INVALID_INPUT,
                "The request body has a content type this endpoint does not take."));
    }

    /** A path no endpoint serves, or a method the path does not take: 404 like any unknown target. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ApiError> notFound(Exception e) {
        return respond(ApiException.notFound());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return respond(new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                ApiErrors.PAYLOAD_TOO_LARGE_MESSAGE));
    }

    @ExceptionHandler(NotImplementedException.class)
    ResponseEntity<ApiError> notImplemented(NotImplementedException e) {
        return respond(new ApiException(HttpStatus.NOT_IMPLEMENTED, ApiErrors.NOT_IMPLEMENTED,
                ApiErrors.NOT_IMPLEMENTED_MESSAGE));
    }

    /** Anything else, a database error included: 500 without retry, logged by class and SQL state only. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Request failed: {}{}", e.getClass().getName(),
                SqlStates.of(e).map(state -> " (SQL state " + state + ")").orElse(""));
        return respond(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrors.INTERNAL_ERROR,
                ApiErrors.INTERNAL_ERROR_MESSAGE));
    }

    private static ResponseEntity<ApiError> respond(ApiException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            response.header("Retry-After", Long.toString(e.retryAfterSeconds()));
        }
        return response.body(e.body());
    }
}
