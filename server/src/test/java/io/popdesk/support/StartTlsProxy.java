package io.popdesk.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * An SMTP server that offers STARTTLS and accepts nothing else until the client has switched to
 * TLS. GreenMail's SMTP server has no STARTTLS, so this sits in front of it: it answers EHLO with
 * STARTTLS, does the TLS handshake with the test certificate ({@link TestTls}), and from then on
 * passes bytes through to GreenMail unchanged. Mail sent through it lands in the same GreenMail.
 */
final class StartTlsProxy {

    private final ServerSocket server;
    private final int backendPort;
    private final SSLContext tls;
    private final AtomicInteger upgraded = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();

    StartTlsProxy(int backendPort, SSLContext tls) {
        this.backendPort = backendPort;
        this.tls = tls;
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        daemon("starttls-accept", this::acceptLoop).start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connections that switched to TLS. */
    int upgradedConnections() {
        return upgraded.get();
    }

    /** Commands refused because the client did not switch to TLS first. */
    int refusedCommands() {
        return refused.get();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                daemon("starttls-session", () -> session(client)).start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void session(Socket client) {
        try (client; Socket backend = new Socket(InetAddress.getLoopbackAddress(), backendPort)) {
            InputStream fromBackend = backend.getInputStream();
            OutputStream toClient = client.getOutputStream();
            // GreenMail's greeting goes to the client as is.
            toClient.write((readLine(fromBackend) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            toClient.flush();

            InputStream fromClient = client.getInputStream();
            while (true) {
                String line = readLine(fromClient);
                if (line == null) {
                    return;
                }
                String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase(Locale.ROOT) : line;
                if (command.equals("EHLO")) {
                    reply(toClient, "250-popdesk-test\r\n250 STARTTLS");
                } else if (command.equals("HELO")) {
                    reply(toClient, "250 popdesk-test");
                } else if (command.equals("QUIT")) {
                    reply(toClient, "221 Bye");
                    return;
                } else if (line.equalsIgnoreCase("STARTTLS")) {
                    reply(toClient, "220 Ready to start TLS");
                    SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(
                            client, client.getInetAddress().getHostAddress(), client.getPort(), false);
                    secure.setUseClientMode(false);
                    secure.startHandshake();
                    upgraded.incrementAndGet();
                    pipe(secure, backend);
                    return;
                } else {
                    refused.incrementAndGet();
                    reply(toClient, "530 Must issue a STARTTLS command first");
                }
            }
        } catch (IOException e) {
            // The client went away or the handshake failed; the client sees that on its side.
        }
    }

    /** Copies both ways until one side closes. */
    private static void pipe(Socket a, Socket b) throws IOException {
        Thread back = daemon("starttls-pipe", () -> copy(b, a));
        back.start();
        copy(a, b);
        try {
            back.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException e) {
            // One side closed.
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException | UnsupportedOperationException ignored) {
                // An SSLSocket cannot half-close; closing the session does it.
            }
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads one CRLF line byte by byte, so nothing after it (the TLS handshake) is read ahead. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.US_ASCII);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
    }

    private static Thread daemon(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        return t;
    }
}
