package io.popdesk.e2e.support;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A {@code multipart/form-data} body. */
public final class Multipart {

    private final String boundary = "e2e-" + UUID.randomUUID();
    private final List<byte[]> chunks = new ArrayList<>();

    public Multipart json(String name, Object value) {
        return part("Content-Disposition: form-data; name=\"" + name + "\"\r\nContent-Type: application/json\r\n",
                Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    public Multipart file(String name, String fileName, String contentType, byte[] content) {
        return part("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n", content);
    }

    private Multipart part(String headers, byte[] content) {
        chunks.add(("--" + boundary + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.UTF_8));
        chunks.add(content);
        chunks.add("\r\n".getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** One array, so the request has a Content-Length like a browser upload, not chunked encoding. */
    public BodyPublisher publisher() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        chunks.forEach(out::writeBytes);
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return BodyPublishers.ofByteArray(out.toByteArray());
    }
}
