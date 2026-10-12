package io.popdesk.helpdesk.web.console;

import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Holds the JSON of an issue staff file for a customer to the limit of the portal's filing
 * (conventions "Error codes", 413): a body over {@link #MAX_BODY_BYTES} is 413
 * {@code PAYLOAD_TOO_LARGE} before any field is looked at. The bytes are counted as they are read, so
 * a chunked body without {@code Content-Length} is held to the same limit.
 */
@ControllerAdvice(assignableTypes = ConsoleIssueController.class)
class FilingBodyLimit extends RequestBodyAdviceAdapter {

    /** 256 KB, the limit of the portal's {@code ReceivedPriority}. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == CreateIssueRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = input.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                    ApiErrors.PAYLOAD_TOO_LARGE_MESSAGE);
        }
        HttpHeaders headers = input.getHeaders();
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
