package io.popdesk.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.imap.commands.IdRange;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedInputStream;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *       connections while the mail stays, and {@link #refuse} can answer the app's request for one mail with
 *       {@code NO} or {@code BAD} or a closed connection (B7-AC-10~15).
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

    /**
     * One mail in a folder: its UID there, the header values a test looks for, its flags, and its raw bytes. The
     * header values and flags are read when the folder is listed; the raw bytes only when {@link #raw()} is called,
     * so listing a folder with large mail in it stays cheap.
     */
    public static final class Stored {

        private final StoredMessage message;
        private final String messageId;
        private final String subject;
        private final boolean seen;
        private final boolean deleted;
        private final Instant receivedAt;

        private Stored(StoredMessage message) {
            this.message = message;
            try {
                MimeMessage mime = message.getMimeMessage();
                this.messageId = mime.getHeader("Message-ID", null);
                this.subject = mime.getSubject();
            } catch (MessagingException e) {
                throw new AssertionError("Could not read a mail in the test mailbox", e);
            }
            this.seen = message.isSet(Flags.Flag.SEEN);
            this.deleted = message.isSet(Flags.Flag.DELETED);
            this.receivedAt = message.getReceivedDate().toInstant();
        }

        public long uid() {
            return message.getUid();
        }

        public String messageId() {
            return messageId;
        }

        public String subject() {
            return subject;
        }

        public boolean seen() {
            return seen;
        }

        public boolean deleted() {
            return deleted;
        }

        public Instant receivedAt() {
            return receivedAt;
        }

        /** The raw bytes of the mail, written out on each call. */
        public byte[] raw() {
            try {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                message.getMimeMessage().writeTo(raw);
                return raw.toByteArray();
            } catch (MessagingException | IOException e) {
                throw new AssertionError("Could not read a mail in the test mailbox", e);
            }
        }
    }

    private static MailInServer shared;

    private final GreenMail smtp;
    private final GreenMail imap;
    private final GreenMailUser user;
    private final Gate gate;

    /** Sent mail already read, by the stored message (GreenMail hands out the same object each time). */
    private final Map<MimeMessage, ReceivedMail> read = new ConcurrentHashMap<>();

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
        gate = new Gate(imap.getImap().getPort(), this);
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

    /** Every mail the app sent so far, oldest first. Each mail is read once; later calls reuse what was read. */
    public List<ReceivedMail> sent() {
        return Arrays.stream(smtp.getReceivedMessages()).map(m -> read.computeIfAbsent(m, MailInServer::read))
                .toList();
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
            out.add(new Stored(m));
        }
        return out;
    }

    /**
     * Removes the mail with this {@code Message-ID} from every folder (flag {@code \Deleted}, then expunge that UID).
     * A test that put in a large mail removes it at its end, so the mailbox of the JVM does not keep it.
     */
    public void remove(String messageId) {
        for (Folder folder : Folder.values()) {
            MailFolder f = folder(folder);
            if (f == null) {
                continue;
            }
            for (Stored m : withMessageId(folder, messageId)) {
                try {
                    f.setFlags(new Flags(Flags.Flag.DELETED), true, m.uid(), null, false);
                } catch (FolderException e) {
                    throw new IllegalStateException("Could not flag a mail in " + folder.folderName(), e);
                }
                f.expunge(new IdRange[] {new IdRange(m.uid())});
            }
        }
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

    /** Takes IMAP connections again, on the same port, and answers every request again ({@link #answerAll()}). */
    public void restore() {
        answerAll();
        gate.open();
    }

    /** A request of the app for one mail that {@link #refuse} can answer in place of GreenMail. */
    public enum Request {
        /** The first part of the raw bytes: {@code BODY.PEEK[]<0.…>}, or {@code BODY.PEEK[]} whole. */
        FIRST_PART,
        /** The second part of 1 MB ({@code mail.imap.fetchsize}): {@code BODY.PEEK[]<1048576.…>}. */
        SECOND_PART,
        /** The header block: {@code BODY.PEEK[HEADER]} or {@code RFC822.HEADER}. */
        HEADER
    }

    /** How the gate answers a refused request. */
    public enum Answer {
        /** A tagged {@code NO}. */
        NO,
        /** A tagged {@code BAD}. */
        BAD,
        /** No answer: the gate closes that connection, as a server that went away while sending. */
        DROP
    }

    /** The text after a refusing {@code NO} or {@code BAD}: no log of the app may hold it (B7-AC-15). */
    public static final String REFUSAL_TEXT = "gate-refusal-text";

    private final Map<String, Map<Request, Answer>> refusals = new ConcurrentHashMap<>();

    /**
     * From now on, answers {@code request} for the mail with this {@code Message-ID} (as written, with the angle
     * brackets) in the incoming folder with {@code answer}, in place of GreenMail. The gate finds the mail's UID and
     * sequence number in GreenMail at each command, and takes {@code FETCH} and {@code UID FETCH} of that one number
     * while the incoming folder is selected; everything else goes to GreenMail as before. Each {@code FETCH} the gate
     * sees while a refusal is set is printed ({@code mailin-gate: …}, the tag left out). Undo with
     * {@link #answerAll()}.
     */
    public void refuse(String messageId, Request request, Answer answer) {
        refusals.computeIfAbsent(messageId, k -> new ConcurrentHashMap<>()).put(request, answer);
    }

    /** Ends every {@link #refuse}: GreenMail answers every request again. */
    public void answerAll() {
        refusals.clear();
    }

    private static final Pattern FETCH = Pattern.compile(
            "^(\\S+) (UID )?FETCH (\\d+) (.*)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SELECT = Pattern.compile(
            "^\\S+ (?:SELECT|EXAMINE) \"?([^\"]*)\"?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PART = Pattern.compile(
            "BODY(?:\\.PEEK)?\\[\\](?:<(\\d+)\\.\\d+>)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern HEADER = Pattern.compile(
            "BODY(?:\\.PEEK)?\\[HEADER\\]|RFC822\\.HEADER", Pattern.CASE_INSENSITIVE);

    /** The folder a {@code SELECT} or {@code EXAMINE} line opens, or null for any other line. */
    private static String selected(String line) {
        Matcher m = SELECT.matcher(line);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * How the gate answers a command line of the client while {@code folder} is selected: null to pass it to
     * GreenMail, else the tag and the answer.
     */
    private Map.Entry<String, Answer> decide(String folder, String line) {
        if (refusals.isEmpty()) {
            return null;
        }
        Matcher m = FETCH.matcher(line);
        if (!m.matches()) {
            return null;
        }
        System.out.println("mailin-gate: " + line.substring(m.group(1).length() + 1));
        if (!Folder.INBOUND.folderName().equals(folder)) {
            return null;
        }
        boolean byUid = m.group(2) != null;
        long number = Long.parseLong(m.group(3));
        String items = m.group(4);
        Request request = request(items);
        if (request == null) {
            return null;
        }
        MailFolder inbound = folder(Folder.INBOUND);
        if (inbound == null) {
            return null;
        }
        for (Map.Entry<String, Map<Request, Answer>> refusal : refusals.entrySet()) {
            Answer answer = refusal.getValue().get(request);
            if (answer == null) {
                continue;
            }
            for (Stored mail : withMessageId(Folder.INBOUND, refusal.getKey())) {
                long mine;
                try {
                    mine = byUid ? mail.uid() : inbound.getMsn(mail.uid());
                } catch (FolderException e) {
                    continue;
                }
                if (mine == number) {
                    System.out.println("mailin-gate: answered " + answer + " for " + request);
                    return Map.entry(m.group(1), answer);
                }
            }
        }
        return null;
    }

    private static Request request(String items) {
        if (HEADER.matcher(items).find()) {
            return Request.HEADER;
        }
        Matcher part = PART.matcher(items);
        if (!part.find()) {
            return null;
        }
        long start = part.group(1) == null ? 0 : Long.parseLong(part.group(1));
        if (start == 0) {
            return Request.FIRST_PART;
        }
        return start == 1024 * 1024 ? Request.SECOND_PART : null;
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

    private static ReceivedMail read(MimeMessage message) {
        try {
            return ReceivedMail.of(message);
        } catch (MessagingException e) {
            throw new AssertionError("Could not read a sent mail", e);
        }
    }

    /**
     * A TCP gate in front of GreenMail's IMAP port. Closing it closes its listening socket and every connection
     * through it; opening it listens on the same port again. It reads what the client sends line by line, so it can
     * answer a refused request itself ({@link MailInServer#refuse}); literals ({@code {n}}) pass unread.
     */
    private static final class Gate {

        private static final Pattern LITERAL = Pattern.compile("\\{(\\d+)\\+?\\}\r?\n$");

        private final int backendPort;
        private final MailInServer mailbox;
        private final int port;
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();
        private ServerSocket server;

        Gate(int backendPort, MailInServer mailbox) {
            this.backendPort = backendPort;
            this.mailbox = mailbox;
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
                        Object toClient = new Object();
                        daemon("mailin-gate-in", () -> commands(client, backend, toClient)).start();
                        daemon("mailin-gate-out", () -> pump(backend, client, toClient)).start();
                    } catch (IOException e) {
                        // The gate was closed, or one connection failed; the loop checks which.
                    }
                }
            }).start();
        }

        /** GreenMail to the client, as it comes; {@code lock} keeps the gate's own answers apart from it. */
        private void pump(Socket from, Socket to, Object lock) {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (lock) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // One side closed; close both below.
            } finally {
                closeBoth(from, to);
            }
        }

        /**
         * The client to GreenMail, one line at a time. A line the mailbox refuses is not passed on: the gate writes
         * the tagged {@code NO} or {@code BAD} to the client itself, or closes the connection ({@code DROP}).
         */
        private void commands(Socket client, Socket backend, Object lock) {
            String selected = null;
            try (InputStream in = new BufferedInputStream(client.getInputStream());
                    OutputStream toBackend = backend.getOutputStream();
                    OutputStream toClient = client.getOutputStream()) {
                byte[] line;
                while ((line = line(in)) != null) {
                    String text = new String(line, StandardCharsets.ISO_8859_1);
                    Matcher literal = LITERAL.matcher(text);
                    if (literal.find()) {
                        toBackend.write(line);
                        toBackend.write(in.readNBytes(Integer.parseInt(literal.group(1))));
                        toBackend.flush();
                        continue;
                    }
                    String command = text.stripTrailing();
                    String folder = selected(command);
                    if (folder != null) {
                        selected = folder;
                    }
                    Map.Entry<String, Answer> refused = mailbox.decide(selected, command);
                    if (refused == null) {
                        toBackend.write(line);
                        toBackend.flush();
                    } else if (refused.getValue() == Answer.DROP) {
                        return;
                    } else {
                        byte[] answer = (refused.getKey() + " " + refused.getValue() + " " + REFUSAL_TEXT + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII);
                        synchronized (lock) {
                            toClient.write(answer);
                            toClient.flush();
                        }
                    }
                }
            } catch (IOException e) {
                // One side closed; close both below.
            } finally {
                closeBoth(client, backend);
            }
        }

        /** The next line with its line break, or what is left at the end; null at the end. */
        private static byte[] line(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                out.write(b);
                if (b == '\n') {
                    break;
                }
            }
            return out.size() == 0 ? null : out.toByteArray();
        }

        private void closeBoth(Socket a, Socket b) {
            closeQuietly(a);
            closeQuietly(b);
            open.remove(a);
            open.remove(b);
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
