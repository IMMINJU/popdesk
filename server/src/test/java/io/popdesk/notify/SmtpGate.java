package io.popdesk.notify;

import io.popdesk.support.MailServer;
import io.popdesk.support.Wait;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A plain SMTP port in front of GreenMail that a test can close for a while, to make sending fail
 * the way a mail server that is down for maintenance does.
 *
 * <ul>
 *   <li>Open ({@link #pass()}, the default): each connection is passed through to GreenMail's plain
 *       port unchanged, so mail lands in {@link MailServer} as usual and fixtures work.
 *   <li>Closed ({@link #hold()}): the gate answers SMTP itself, takes the whole message, keeps it
 *       ({@link #held()}), and refuses it at the end of {@code DATA} with {@code 451}. The app sees
 *       a failed send; the test can still read what the app tried to send.
 * </ul>
 *
 * The state is read when a connection opens. No STARTTLS is offered, so the app reaches the gate
 * only with {@code SMTP_ALLOW_PLAINTEXT=true}. Any {@code AUTH PLAIN} or {@code AUTH LOGIN} is
 * accepted while closed. One gate for the whole JVM ({@link #shared()}).
 */
final class SmtpGate {

    /** A message the gate refused while closed. */
    record Held(String from, List<String> recipients, String raw, String text) {

        boolean isTo(String address) {
            return recipients.stream().anyMatch(r -> r.equalsIgnoreCase(address));
        }
    }

    private static SmtpGate shared;

    private final ServerSocket server;
    private final int backendPort;
    private final List<Held> held = new CopyOnWriteArrayList<>();
    private volatile boolean holding;

    private SmtpGate(int backendPort) {
        this.backendPort = backendPort;
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        daemon("smtp-gate-accept", this::acceptLoop).start();
    }

    static synchronized SmtpGate shared() {
        if (shared == null) {
            shared = new SmtpGate(MailServer.shared().plainPort());
        }
        return shared;
    }

    int port() {
        return server.getLocalPort();
    }

    /** From now on, refuse every new connection's mail with 451 and keep it. */
    void hold() {
        holding = true;
    }

    /** From now on, pass new connections through to GreenMail. */
    void pass() {
        holding = false;
    }

    /** Messages refused while closed, oldest first. */
    List<Held> held() {
        return List.copyOf(held);
    }

    /** Waits for a refused message to {@code address}. */
    Held awaitHeldTo(String address) {
        return Wait.until("a held mail to " + address,
                () -> held.stream().filter(h -> h.isTo(address)).findFirst().orElse(null));
    }

    /** Opens the gate and forgets the held messages. */
    void reset() {
        holding = false;
        held.clear();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                boolean hold = holding;
                daemon("smtp-gate-session", () -> {
                    if (hold) {
                        holdSession(client);
                    } else {
                        passSession(client);
                    }
                }).start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void passSession(Socket client) {
        try (client; Socket backend = new Socket(InetAddress.getLoopbackAddress(), backendPort)) {
            Thread back = daemon("smtp-gate-pipe", () -> copy(backend, client));
            back.start();
            copy(client, backend);
            back.join(30_000);
        } catch (IOException e) {
            // One side went away; the app sees that on its side.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void holdSession(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            reply(out, "220 popdesk-gate ESMTP");
            String from = null;
            List<String> recipients = new ArrayList<>();
            while (true) {
                String line = readLine(in);
                if (line == null) {
                    return;
                }
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EHLO")) {
                    reply(out, "250-popdesk-gate\r\n250-AUTH PLAIN LOGIN\r\n250 8BITMIME");
                } else if (upper.startsWith("HELO")) {
                    reply(out, "250 popdesk-gate");
                } else if (upper.equals("AUTH PLAIN")) {
                    reply(out, "334 ");
                    readLine(in);
                    reply(out, "235 2.7.0 Accepted");
                } else if (upper.startsWith("AUTH PLAIN ")) {
                    reply(out, "235 2.7.0 Accepted");
                } else if (upper.equals("AUTH LOGIN")) {
                    reply(out, "334 VXNlcm5hbWU6");
                    readLine(in);
                    reply(out, "334 UGFzc3dvcmQ6");
                    readLine(in);
                    reply(out, "235 2.7.0 Accepted");
                } else if (upper.startsWith("AUTH LOGIN ")) {
                    reply(out, "334 UGFzc3dvcmQ6");
                    readLine(in);
                    reply(out, "235 2.7.0 Accepted");
                } else if (upper.startsWith("MAIL FROM:")) {
                    from = address(line.substring(10));
                    recipients.clear();
                    reply(out, "250 2.1.0 OK");
                } else if (upper.startsWith("RCPT TO:")) {
                    recipients.add(address(line.substring(8)));
                    reply(out, "250 2.1.5 OK");
                } else if (upper.equals("DATA")) {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    String raw = readData(in);
                    held.add(new Held(from, List.copyOf(recipients), raw, text(raw)));
                    reply(out, "451 4.3.0 Held by the test, try again later");
                } else if (upper.equals("RSET")) {
                    from = null;
                    recipients.clear();
                    reply(out, "250 2.0.0 OK");
                } else if (upper.equals("NOOP")) {
                    reply(out, "250 2.0.0 OK");
                } else if (upper.equals("QUIT")) {
                    reply(out, "221 2.0.0 Bye");
                    return;
                } else {
                    reply(out, "502 5.5.1 Not implemented");
                }
            }
        } catch (IOException e) {
            // The app went away.
        }
    }

    private static String address(String value) {
        String v = value.strip();
        int lt = v.indexOf('<');
        int gt = v.indexOf('>');
        return lt >= 0 && gt > lt ? v.substring(lt + 1, gt) : v.split("\\s")[0];
    }

    /** The message up to the line with a single dot, with dot-stuffing undone. Bytes kept as Latin-1. */
    private static String readData(InputStream in) throws IOException {
        StringBuilder data = new StringBuilder();
        while (true) {
            String line = readLine(in);
            if (line == null || line.equals(".")) {
                return data.toString();
            }
            data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
        }
    }

    /** The text of every text part, decoded, as {@code ReceivedMail} reads it. */
    private static String text(String raw) {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
            StringBuilder out = new StringBuilder();
            collectText(message, out);
            return out.toString();
        } catch (MessagingException | IOException e) {
            return raw;
        }
    }

    private static void collectText(Part part, StringBuilder out) throws MessagingException, IOException {
        if (part.isMimeType("text/*")) {
            out.append(part.getContent()).append('\n');
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectText(multipart.getBodyPart(i), out);
            }
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
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** One CRLF line, bytes read as Latin-1 so 8-bit message bytes survive. Null at the end of the stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static Thread daemon(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        return t;
    }
}
