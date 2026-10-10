package io.popdesk.attachment;

import io.popdesk.support.ApiClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request on a raw socket that the test holds open: an upload whose body is only partly sent,
 * or a download whose response body the test does not read. {@link java.net.http.HttpClient} sends
 * a whole body and reads a whole response, so it cannot keep a request running.
 *
 * <p>The request carries the client's {@code Host}, {@code X-Forwarded-For}, cookies, and CSRF
 * token, like {@link ApiClient}. Every failure is an {@link AssertionError}.
 */
final class HeldRequest implements AutoCloseable {

    private static final Pattern STATUS = Pattern.compile("^HTTP/1\\.1 (\\d{3})");

    private static final int READ_TIMEOUT_MS = 60_000;

    /** How long a download waits for the response head. */
    private static final int HEAD_TIMEOUT_MS = 15_000;

    /** How much of a body that is not the file is kept for a failure message. */
    private static final int SNIPPET_BYTES = 1024;

    /** How much {@link #probeAfterTimeout()} reads at most; far more than the receive buffer holds. */
    private static final int PROBE_BYTES = 1024 * 1024;

    private static final int PROBE_IDLE_MS = 2_000;

    private final Socket socket;
    private final byte[] rest;
    private final Head head;

    private HeldRequest(Socket socket, byte[] rest, Head head) {
        this.socket = socket;
        this.rest = rest;
        this.head = head;
    }

    /**
     * What a download read of its response: the status (-1 without a status line),
     * {@code Content-Length} (-1 without one), the start of the body when the status is not 200,
     * and why the head could not be read, if so.
     */
    private record Head(int status, long contentLength, String bodyStart, String problem) {}

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
        return new HeldRequest(socket, rest, null);
    }

    /**
     * Sends {@code GET path}, reads the response up to the end of its headers, and does not read the
     * body. The socket's receive buffer is small, so the app's writes of a large file block until the
     * test reads or closes. A response that is not 200 has the start of its body kept too. A head
     * that does not arrive is not a failure here: {@link #status()} is then -1 and
     * {@link #describe()} says why.
     */
    static HeldRequest download(ApiClient client, String path) {
        String head = "GET " + path + " HTTP/1.1\r\n" + headers(client) + "\r\n";
        Socket socket = open(client, 4096);
        write(socket, head.getBytes(StandardCharsets.UTF_8), new byte[0]);
        return new HeldRequest(socket, new byte[0], readHead(socket));
    }

    /** The status of a download's response, or -1 when no status line arrived. */
    int status() {
        return head == null ? -1 : head.status();
    }

    /** The {@code Content-Length} of a download's response, or -1 when there is none. */
    long contentLength() {
        return head == null ? -1 : head.contentLength();
    }

    /** The status, {@code Content-Length}, and kept start of the body of a download, for a failure message. */
    String describe() {
        if (head == null) {
            return "no response read (an upload)";
        }
        if (head.problem() != null) {
            return "no response head: " + head.problem();
        }
        String out = "status " + head.status() + ", Content-Length "
                + (head.contentLength() < 0 ? "none" : Long.toString(head.contentLength()));
        return head.bodyStart() == null ? out : out + ", body starts '" + head.bodyStart() + "'";
    }

    /**
     * Says whether the app still holds this download: how many bytes wait unread, then what reading
     * on gives (more of the file, the end of the stream, a reset, or nothing). Reading lets the app
     * write again, so this ends the hold; call it only after the test has already failed.
     */
    String probeAfterTimeout() {
        long read = 0;
        try {
            InputStream in = socket.getInputStream();
            int waiting = in.available();
            socket.setSoTimeout(PROBE_IDLE_MS);
            byte[] buffer = new byte[8192];
            String end = "still sending";
            while (read < PROBE_BYTES) {
                int n = in.read(buffer);
                if (n < 0) {
                    end = "closed by the app";
                    break;
                }
                read += n;
            }
            return waiting + " bytes waiting unread; read " + read + " more bytes, then " + end;
        } catch (SocketTimeoutException e) {
            return "read " + read + " more bytes, then nothing for " + PROBE_IDLE_MS + " ms (open, not sending)";
        } catch (IOException e) {
            return "read " + read + " more bytes, then " + e;
        }
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
            socket.setSoTimeout(READ_TIMEOUT_MS);
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

    /**
     * Reads the status line and the headers, one byte at a time so that nothing of the body is taken
     * from the socket, and for a status other than 200 the start of the body.
     */
    private static Head readHead(Socket socket) {
        int status = -1;
        try {
            socket.setSoTimeout(HEAD_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            Matcher m = STATUS.matcher(statusLine);
            if (!m.find()) {
                return new Head(-1, -1, null, "no HTTP status line: '" + statusLine + "'");
            }
            status = Integer.parseInt(m.group(1));
            long contentLength = -1;
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).strip().toLowerCase(Locale.ROOT).equals("content-length")) {
                    contentLength = Long.parseLong(line.substring(colon + 1).strip());
                }
            }
            String bodyStart = status == 200 ? null : readBodyStart(socket, in, contentLength);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            return new Head(status, contentLength, bodyStart, null);
        } catch (IOException | NumberFormatException e) {
            return new Head(status, -1, null, (status < 0 ? "" : "status " + status + ", then ") + e);
        }
    }

    /** One line without its line end; empty at the blank line and at the end of the stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        return line.toString(StandardCharsets.ISO_8859_1).strip();
    }

    /** Up to {@link #SNIPPET_BYTES} of a body, as far as it arrives within two seconds of quiet. */
    private static String readBodyStart(Socket socket, InputStream in, long contentLength) throws IOException {
        long want = contentLength < 0 ? SNIPPET_BYTES : Math.min(contentLength, SNIPPET_BYTES);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        socket.setSoTimeout(2_000);
        try {
            int b;
            while (body.size() < want && (b = in.read()) != -1) {
                body.write(b);
            }
        } catch (SocketTimeoutException e) {
            // A body without Content-Length (chunked) ends by going quiet; what arrived is the start.
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private static int readStatus(InputStream in) throws IOException {
        String status = readLine(in);
        Matcher m = STATUS.matcher(status);
        if (!m.find()) {
            throw new AssertionError("A held request got no HTTP status line: '" + status + "'");
        }
        return Integer.parseInt(m.group(1));
    }
}
