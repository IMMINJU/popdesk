package io.popdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;

/**
 * Reads a whole number from a multipart form field without its own content type, such as
 * {@code commentId} next to an uploaded file (docs/integrate/openapi-*.json, {@code uploadAttachment}).
 * Spring MVC reads a {@code @RequestPart} that is not a file with message converters, and none of the
 * default ones turns plain text into a {@link Long}. Only reads; it never writes a response.
 */
@Component
class PartNumberConverter extends AbstractHttpMessageConverter<Long> {

    /** A form field is a few digits; anything longer is not a number this app takes. */
    private static final int MAX_LENGTH = 32;

    PartNumberConverter() {
        super(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM);
    }

    @Override
    protected boolean supports(Class<?> type) {
        return type == Long.class;
    }

    @Override
    protected boolean canWrite(MediaType mediaType) {
        return false;
    }

    @Override
    protected Long readInternal(Class<? extends Long> type, HttpInputMessage message) throws IOException {
        byte[] bytes = message.getBody().readNBytes(MAX_LENGTH + 1);
        String text = new String(bytes, StandardCharsets.US_ASCII).strip();
        if (bytes.length > MAX_LENGTH || !text.matches("-?[0-9]{1,18}")) {
            throw new HttpMessageNotReadableException("The form field is not a whole number.", message);
        }
        return Long.valueOf(text);
    }

    @Override
    protected void writeInternal(Long value, HttpOutputMessage message) {
        throw new UnsupportedOperationException("Only reads form fields");
    }
}
