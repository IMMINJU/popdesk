package io.popdesk.helpdesk.web.portal;

import io.popdesk.helpdesk.Priority;
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
 * Keeps an unknown {@code priority} of a filing out of JSON binding, so that the filing answers 400
 * with every invalid field at once (S0-AC-42) instead of failing on the enum alone. The value sent is
 * kept as a request attribute and checked by the service with the other fields. Applies to the JSON
 * body and to the {@code issue} part of a multipart filing.
 */
@ControllerAdvice(assignableTypes = PortalIssueController.class)
class ReceivedPriority extends RequestBodyAdviceAdapter {

    private static final String ATTRIBUTE = ReceivedPriority.class.getName();
    private static final Set<String> KNOWN = Arrays.stream(Priority.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The priority the request sent when it is not a known value; null otherwise. */
    static String unknown() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null ? null : (String) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == CreateIssueRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = input.getBody().readAllBytes();
        try {
            JsonNode tree = JSON.readTree(body);
            JsonNode priority = tree instanceof ObjectNode ? tree.get("priority") : null;
            if (priority != null && priority.isString() && !KNOWN.contains(priority.stringValue())) {
                RequestContextHolder.currentRequestAttributes().setAttribute(ATTRIBUTE, priority.stringValue(),
                        RequestAttributes.SCOPE_REQUEST);
                ((ObjectNode) tree).remove("priority");
                body = JSON.writeValueAsBytes(tree);
            }
        } catch (JacksonException e) {
            throw new HttpMessageNotReadableException("The request body is not JSON.", e, input);
        }
        return replay(input.getHeaders(), body);
    }

    private static HttpInputMessage replay(HttpHeaders headers, byte[] body) {
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
