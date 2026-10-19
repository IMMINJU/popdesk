package io.popdesk.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The mail servers of the tests that receive email (slice 12), one set for the whole JVM ({@link #shared()}), apart
 * from {@link MailServer}: {@code ConnectedAppTest} clears every mailbox of that one before each test, and the
 * mailbox here must keep what other tests left in it.
 *
 * <ul>
 *   <li>SMTP ({@link #smtpPort()}): the app's outgoing mail. Accepts {@link MailServer#USER}/{@link MailServer#PASSWORD}
 *       and stores mail for any recipient; read it with {@link #sent()}.
 *   <li>IMAP ({@link #imapPort()}, plain text, no STARTTLS): the mailbox the app reads, account {@link #IMAP_USER} of
 *       {@link #ADDRESS}, with its three folders ({@link Folder}). Put mail in with {@link #deliver}; read a folder
 *       with {@link #messages(Folder)}. The port is a gate in front of GreenMail, so {@link #cut()} can refuse
 *       connections while the mail stays.
 * </ul>
 *
 * Mail from other tests stays in the folders; find yours by the {@code Message-ID} or subject you made. A test that
 * cuts the server, changes the password, or deletes a folder puts it back at its end ({@link #restore()},
 * {@link #restorePassword()}, {@link #createFolder}).
 */
public final class MailInServer {

    public static final String HOST = "127.0.0.1";

    /** {@code MAIL_INBOUND_ADDRESS}, and the email of the IMAP account. */
    public static final String ADDRESS = "support@popdesk.test";

    public static final String IMAP_USER = "support";
    public static final String IMAP_PASSWORD = "imap-secret";

    /** The three folders, by the names the app gets in its setting keys. */
    public enum Folder {
        /** {@code IMAP_FOLDER}: where mail comes in. Not {@code INBOX}, so a test can delete it. */
        INBOUND("popdesk-in"),
        /** {@code IMAP_PROCESSED_FOLDER}. */
        PROCESSED("popdesk-processed"),
        /** {@code IMAP_QUARANTINE_FOLDER}. */
        QUARANTINE("popdesk-quarantine");

        private final String folderName;

        Folder(String folderName) {
            this.folderName = folderName;
        }

        public String folderName() {
            return folderName;
        }
    }

    /** One mail in a folder: its UID there, the header values a test looks for, its flags, and its raw bytes. */
    public record Stored(long uid, String messageId, String subject, boolean seen, boolean deleted, Instant receivedAt,
            byte[] raw) {}

    private static MailInServer shared;

    private final GreenMail smtp;
    private final GreenMail imap;
    private final GreenMailUser user;
    private final Gate gate;

    private MailInServer() {
        smtp = new GreenMail(new ServerSetup(0, HOST, ServerSetup.PROTOCOL_SMTP))
                .withConfiguration(GreenMailConfiguration.aConfig().withUser(MailServer.USER, MailServer.PASSWORD));
        smtp.start();
        imap = new GreenMail(new ServerSetup(0, HOST, ServerSetup.PROTOCOL_IMAP));
        imap.start();
        user = imap.setUser(ADDRESS, IMAP_USER, IMAP_PASSWORD);
        for (Folder folder : Folder.values()) {
            createFolder(folder);
        }
        gate = new Gate(imap.getImap().getPort());
    }

    /** The servers for this JVM, started on first use, with the three folders made. */
    public static synchronized MailInServer shared() {
        if (shared == null) {
            shared = new MailInServer();
        }
        return shared;
    }

    /** {@code SMTP_PORT} of the app. */
    public int smtpPort() {
        return smtp.getSmtp().getPort();
    }

    /** {@code IMAP_PORT} of the app: the gate in front of GreenMail's IMAP. */
    public int imapPort() {
        return gate.port();
    }

    // Outgoing mail

    /** Every mail the app sent so far, oldest first. */
    public List<ReceivedMail> sent() {
        return Arrays.stream(smtp.getReceivedMessages()).map(MailInServer::read).toList();
    }

    /** Mail the app sent to {@code address} (ignoring case), oldest first. */
    public List<ReceivedMail> sentTo(String address) {
        return sent().stream().filter(m -> m.isTo(address)).toList();
    }

    /** Waits for the first sent mail that {@code filter} takes; fails with an AssertionError when none comes. */
    public ReceivedMail awaitSent(String what, Predicate<ReceivedMail> filter) {
        return Wait.until(what, () -> sent().stream().filter(filter).findFirst().orElse(null));
    }

    // The mailbox

    /** Puts raw mail bytes as they are (no {@code saveChanges}) into the incoming folder, unread, received now. */
    public void deliver(byte[] raw) {
        deliver(Folder.INBOUND, raw, new Flags(), Instant.now());
    }

    /** Puts raw mail bytes into a folder with these flags and this {@code INTERNALDATE}. */
    public void deliver(Folder folder, byte[] raw, Flags flags, Instant receivedAt) {
        MailFolder target = folder(folder);
        if (target == null) {
            throw new AssertionError("No folder " + folder.folderName() + " in the test mailbox");
        }
        target.appendMessage(parse(raw), flags, Date.from(receivedAt));
    }

    /** The mail in a folder, by UID; empty when the folder does not exist. */
    public List<Stored> messages(Folder folder) {
        MailFolder f = folder(folder);
        if (f == null) {
            return List.of();
        }
        List<Stored> out = new ArrayList<>();
        for (StoredMessage m : f.getMessages()) {
            out.add(stored(m));
        }
        return out;
    }

    /** The mail in a folder whose {@code Message-ID} is {@code messageId} (as written, with the angle brackets). */
    public List<Stored> withMessageId(Folder folder, String messageId) {
        return messages(folder).stream().filter(m -> messageId.equals(m.messageId())).toList();
    }

    /** The mail in a folder whose subject holds {@code text}: for mail without a {@code Message-ID}. */
    public List<Stored> withSubject(Folder folder, String text) {
        return messages(folder).stream().filter(m -> m.subject() != null && m.subject().contains(text)).toList();
    }

    /** The folders that hold mail with this {@code Message-ID}, once each, in the order of {@link Folder}. */
    public List<Folder> foldersOf(String messageId) {
        return Arrays.stream(Folder.values()).filter(f -> !withMessageId(f, messageId).isEmpty()).toList();
    }

    public boolean hasFolder(Folder folder) {
        return folder(folder) != null;
    }

    /** Makes the folder when it is missing. */
    public void createFolder(Folder folder) {
        if (folder(folder) != null) {
            return;
        }
        try {
            manager().createMailbox(user, folder.folderName());
        } catch (Exception e) {
            throw new IllegalStateException("Could not create folder " + folder.folderName(), e);
        }
    }

    /** Deletes the folder and the mail in it (for {@code FOLDER} failures and for folders the app must make). */
    public void deleteFolder(Folder folder) {
        if (folder(folder) == null) {
            return;
        }
        try {
            manager().deleteMailbox(user, folder.folderName());
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete folder " + folder.folderName(), e);
        }
    }

    // Failures

    /** Refuses IMAP connections and drops the open ones, as a stopped server would; the mail stays. */
    public void cut() {
        gate.close();
    }

    /** Takes IMAP connections again, on the same port. */
    public void restore() {
        gate.open();
    }

    /** Changes the IMAP account's password, so the app's sign-in is refused. */
    public void changePassword(String password) {
        user.setPassword(password);
    }

    /** Gives the IMAP account its password {@link #IMAP_PASSWORD} again. */
    public void restorePassword() {
        user.setPassword(IMAP_PASSWORD);
    }

    private ImapHostManager manager() {
        return imap.getManagers().getImapHostManager();
    }

    private MailFolder folder(Folder folder) {
        try {
            return manager().getFolder(user, folder.folderName(), false);
        } catch (Exception e) {
            return null;
        }
    }

    private static MimeMessage parse(byte[] raw) {
        try {
            return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
        } catch (MessagingException e) {
            throw new AssertionError("Could not read the raw mail", e);
        }
    }

    private static Stored stored(StoredMessage m) {
        try {
            MimeMessage mime = m.getMimeMessage();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            mime.writeTo(raw);
            return new Stored(m.getUid(), mime.getHeader("Message-ID", null), mime.getSubject(),
                    m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.DELETED), m.getReceivedDate().toInstant(),
                    raw.toByteArray());
        } catch (MessagingException | IOException e) {
            throw new AssertionError("Could not read a mail in the test mailbox", e);
        }
    }

    private static ReceivedMail read(MimeMessage message) {
        try {
            return ReceivedMail.of(message);
        } catch (MessagingException e) {
            throw new AssertionError("Could not read a sent mail", e);
        }
    }

    /**
     * A plain TCP gate in front of GreenMail's IMAP port. Closing it closes its listening socket and every connection
     * through it; opening it listens on the same port again.
     */
    private static final class Gate {

        private final int backendPort;
        private final int port;
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();
        private ServerSocket server;

        Gate(int backendPort) {
            this.backendPort = backendPort;
            this.server = listen(0);
            this.port = server.getLocalPort();
            start(server);
        }

        int port() {
            return port;
        }

        synchronized void close() {
            closeQuietly(server);
            for (Socket s : open) {
                closeQuietly(s);
            }
            open.clear();
        }

        synchronized void open() {
            if (!server.isClosed()) {
                return;
            }
            server = listen(port);
            start(server);
        }

        private static ServerSocket listen(int port) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
                return s;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not listen on IMAP gate port " + port, e);
            }
        }

        private void start(ServerSocket listening) {
            daemon("mailin-gate-accept", () -> {
                while (!listening.isClosed()) {
                    try {
                        Socket client = listening.accept();
                        Socket backend = new Socket(InetAddress.getLoopbackAddress(), backendPort);
                        open.add(client);
                        open.add(backend);
                        daemon("mailin-gate-in", () -> pump(client, backend)).start();
                        daemon("mailin-gate-out", () -> pump(backend, client)).start();
                    } catch (IOException e) {
                        // The gate was closed, or one connection failed; the loop checks which.
                    }
                }
            }).start();
        }

        private void pump(Socket from, Socket to) {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // One side closed; close both below.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
                open.remove(from);
                open.remove(to);
            }
        }

        private static void closeQuietly(java.io.Closeable c) {
            try {
                c.close();
            } catch (IOException e) {
                // Already closed.
            }
        }

        private static Thread daemon(String name, Runnable body) {
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            return t;
        }
    }
}
