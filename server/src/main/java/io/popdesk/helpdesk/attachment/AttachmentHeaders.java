package io.popdesk.helpdesk.attachment;

import io.popdesk.helpdesk.attachment.AttachmentRepository.AttachmentRow;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The headers of a download and of its {@code HEAD} (S0-AC-59): always {@code Content-Disposition:
 * attachment}, never inline, whatever the file's type. {@code X-Content-Type-Options: nosniff} comes
 * with the security headers of every response. Spring MVC adds {@code Content-Length} and
 * {@code Accept-Ranges} when it writes the contents.
 */
public final class AttachmentHeaders {

    private AttachmentHeaders() {}

    public static HttpHeaders of(AttachmentRow attachment) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(attachment.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(attachment.fileName(), StandardCharsets.UTF_8).build());
        return headers;
    }
}
