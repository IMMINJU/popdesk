package io.popdesk.deploy;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Large attachment uploads and downloads that stream: the file is made from a seed while it is
 * sent, and a download is hashed while it is read, so twenty 10MB transfers at once do not need
 * 200MB in the test JVM. Uses the cookies and CSRF token of an {@link ApiClient} session.
 */
final class Transfer {

    /** 10MB as the slice counts it: 2^20 bytes per MB (tasks.md section 6). */
    static final int TEN_MB = 10 * 1024 * 1024;

    /** How a transfer ended: HTTP status, the attachment ID (uploads), the content hash. */
    record Outcome(String what, int status, String attachmentId, String sha256, String error) {

        @Override
        public String toString() {
            return what + " -> " + (error != null ? error : status + " " + sha256);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    private Transfer() {}

    /** The bytes made from {@code seed}, {@code length} long, the same every time. */
    static InputStream content(long seed, int length) {
        return new InputStream() {
            private final SplittableRandom random = new SplittableRandom(seed);
            private int left = length;

            @Override
            public int read() {
                if (left == 0) {
                    return -1;
                }
                left--;
                return random.nextInt(256);
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left == 0) {
                    return -1;
                }
                int n = Math.min(len, left);
                for (int i = 0; i < n; i++) {
                    b[off + i] = (byte) random.nextInt(256);
                }
                left -= n;
                return n;
            }
        };
    }

    static String sha256(long seed, int length) {
        try (InputStream in = content(seed, length)) {
            return sha256(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Uploads a {@code length}-byte file made from {@code seed} to an issue. */
    static Outcome upload(Person person, String issueKey, long seed, int length) {
        String what = "upload " + seed + " to " + issueKey;
        String boundary = "popdesk-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"load-" + seed
                + ".bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long total = head.length + (long) length + tail.length;
        ApiClient client = person.client();
        HttpRequest request = base(client, person.api() + "/issues/" + issueKey + "/attachments")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header(ApiClient.CSRF_HEADER, client.csrfToken())
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new SequenceInputStream(Collections.enumeration(List.of(
                                new ByteArrayInputStream(head), content(seed, length), new ByteArrayInputStream(tail))))),
                        total))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String id = null;
            if (response.statusCode() == 201) {
                id = JSON.readTree(response.body()).path("id").asString(null);
            }
            return new Outcome(what, response.statusCode(), id, sha256(seed, length),
                    response.statusCode() == 201 ? null : response.statusCode() + " " + Stack.tail(response.body()));
        } catch (IOException e) {
            return new Outcome(what, -1, null, null, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(what, -1, null, null, e.toString());
        }
    }

    /** Downloads an attachment and hashes it while reading. */
    static Outcome download(Person person, String issueKey, String attachmentId) {
        String what = "download " + attachmentId + " of " + issueKey;
        HttpRequest request = base(person.client(), person.api() + "/issues/" + issueKey + "/attachments/"
                + attachmentId).GET().build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                String hash = sha256(body);
                return new Outcome(what, response.statusCode(), attachmentId, hash,
                        response.statusCode() == 200 ? null : "status " + response.statusCode());
            }
        } catch (IOException e) {
            return new Outcome(what, -1, attachmentId, null, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(what, -1, attachmentId, null, e.toString());
        }
    }

    private static HttpRequest.Builder base(ApiClient client, String path) {
        String cookies = client.cookies().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + client.port() + path))
                .timeout(Duration.ofMinutes(3))
                .header("Host", client.host());
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
        if (client.forwardedFor() != null) {
            builder.header(ApiClient.FORWARDED_FOR, client.forwardedFor());
        }
        return builder;
    }

    private static String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
