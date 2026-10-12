package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.IssueStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps an unknown {@code status} of a status change out of JSON binding, so that the issue is looked
 * for first: an issue the caller cannot see is 404 whatever the body says, and only then is the unknown
 * name 400 (conventions "Order of checks"). The name sent is kept as a request attribute and checked
 * by the service. The same way as the portal's {@code ReceivedPriority}.
 */
@ControllerAdvice(assignableTypes = ConsoleIssueController.class)
class ReceivedStatus extends RequestBodyAdviceAdapter {

    /** Far more than {@code {"status": "..."}} needs; a longer body is not read. */
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private static final String ATTRIBUTE = ReceivedStatus.class.getName();
    private static final Set<String> KNOWN = Arrays.stream(IssueStatus.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The status the request sent when it is not a known name; null otherwise. */
    static String unknown() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null ? null : (String) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == ChangeIssueStatusRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = input.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new HttpMessageNotReadableException("The request body is too long for a status.", input);
        }
        try {
            JsonNode tree = JSON.readTree(body);
            JsonNode status = tree instanceof ObjectNode ? tree.get("status") : null;
            if (status != null && status.isString() && !KNOWN.contains(status.stringValue())) {
                RequestContextHolder.currentRequestAttributes().setAttribute(ATTRIBUTE, status.stringValue(),
                        RequestAttributes.SCOPE_REQUEST);
                ((ObjectNode) tree).remove("status");
                body = JSON.writeValueAsBytes(tree);
            }
        } catch (JacksonException e) {
            throw new HttpMessageNotReadableException("The request body is not JSON.", e, input);
        }
        byte[] replayed = body;
        HttpHeaders headers = input.getHeaders();
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(replayed);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
