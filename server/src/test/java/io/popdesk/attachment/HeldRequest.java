package io.popdesk.attachment;

import io.popdesk.support.ApiClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request on a raw socket that the test holds open: an upload whose body is only partly sent,
 * or a download whose response the test does not read. {@link java.net.http.HttpClient} sends a
 * whole body and reads a whole response, so it cannot keep a request running.
 *
 * <p>The request carries the client's {@code Host}, {@code X-Forwarded-For}, cookies, and CSRF
 * token, like {@link ApiClient}. Every failure is an {@link AssertionError}.
 */
final class HeldRequest implements AutoCloseable {

    private static final Pattern STATUS = Pattern.compile("^HTTP/1\\.1 (\\d{3})");

    private final Socket socket;
    private final byte[] rest;

    private HeldRequest(Socket socket, byte[] rest) {
        this.socket = socket;
        this.rest = rest;
    }

    /**
     * Starts a multipart upload of {@code content} as part {@code file}, sends the headers and the
     * first {@code sentBytes} of the body, and stops there. The rest goes with {@link #finish()}.
     */
    static HeldRequest upload(ApiClient client, String path, String fileName, byte[] content, int sentBytes) {
        String boundary = "popdesk-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] all = body.toByteArray();
        if (sentBytes >= all.length) {
            throw new IllegalArgumentException("Send less than the whole body of " + all.length + " bytes");
        }
        String head = "POST " + path + " HTTP/1.1\r\n"
                + headers(client)
                + ApiClient.CSRF_HEADER + ": " + client.csrfToken() + "\r\n"
                + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                + "Content-Length: " + all.length + "\r\n"
                + "\r\n";
        Socket socket = open(client, 0);
        byte[] first = new byte[sentBytes];
        System.arraycopy(all, 0, first, 0, sentBytes);
        byte[] rest = new byte[all.length - sentBytes];
        System.arraycopy(all, sentBytes, rest, 0, rest.length);
        write(socket, head.getBytes(StandardCharsets.UTF_8), first);
        return new HeldRequest(socket, rest);
    }

    /**
     * Sends {@code GET path} and does not read the response. The socket's receive buffer is small,
     * so the app's writes of a large file block until the test reads or closes.
     */
    static HeldRequest download(ApiClient client, String path) {
        String head = "GET " + path + " HTTP/1.1\r\n" + headers(client) + "\r\n";
        Socket socket = open(client, 4096);
        write(socket, head.getBytes(StandardCharsets.UTF_8), new byte[0]);
        return new HeldRequest(socket, new byte[0]);
    }

    /** Sends the rest of the body and returns the response status. */
    int finish() {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(rest);
            out.flush();
            return readStatus(socket.getInputStream());
        } catch (IOException e) {
            throw new AssertionError("A held request failed while finishing: " + e, e);
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is how the test lets the app go; a failure here changes nothing.
        }
    }

    private static String headers(ApiClient client) {
        StringBuilder out = new StringBuilder()
                .append("Host: ").append(client.host()).append("\r\n");
        if (client.forwardedFor() != null) {
            out.append(ApiClient.FORWARDED_FOR).append(": ").append(client.forwardedFor()).append("\r\n");
        }
        Map<String, String> cookies = client.cookies();
        if (!cookies.isEmpty()) {
            StringBuilder cookie = new StringBuilder();
            cookies.forEach((name, value) -> {
                if (!cookie.isEmpty()) {
                    cookie.append("; ");
                }
                cookie.append(name).append('=').append(value);
            });
            out.append("Cookie: ").append(cookie).append("\r\n");
        }
        return out.toString();
    }

    private static Socket open(ApiClient client, int receiveBuffer) {
        try {
            Socket socket = new Socket();
            if (receiveBuffer > 0) {
                socket.setReceiveBufferSize(receiveBuffer);
            }
            socket.setSoTimeout(60_000);
            socket.connect(new InetSocketAddress("127.0.0.1", client.port()), 10_000);
            return socket;
        } catch (IOException e) {
            throw new AssertionError("Could not connect to the app: " + e, e);
        }
    }

    private static void write(Socket socket, byte[] head, byte[] body) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(head);
            out.write(body);
            out.flush();
        } catch (IOException e) {
            throw new AssertionError("Could not send a held request: " + e, e);
        }
    }

    private static int readStatus(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        String status = line.toString(StandardCharsets.ISO_8859_1).strip();
        Matcher m = STATUS.matcher(status);
        if (!m.find()) {
            throw new AssertionError("A held request got no HTTP status line: '" + status + "'");
        }
        return Integer.parseInt(m.group(1));
    }
}
