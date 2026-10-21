package io.popdesk.helpdesk.mailin;

import io.popdesk.helpdesk.mailin.MailInProcessor.Destination;
import io.popdesk.helpdesk.mailin.MailInProcessor.Incoming;
import io.popdesk.notification.MailInState.FailureKind;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The IMAP mailbox of receiving email (design.md 0절 14, S12-AC-06, S12-AC-07, S12-AC-43): one connection per round,
 * with the properties of {@link MailInSettings#imapProperties()}. Mail is read with {@code BODY.PEEK}, so its read mark
 * stays as it is; mail a person marked deleted is left alone; handled mail is moved with {@code UID MOVE} (MOVE and
 * UIDPLUS), with {@code MOVE} by sequence number (MOVE alone), else with {@code COPY}, {@code \Deleted}, and a
 * {@code UID EXPUNGE} of that mail alone (UIDPLUS). A plain {@code EXPUNGE} is never sent: it would take other mail a
 * person marked deleted.
 *
 * <p>What the mail library says of a failure can hold headers and addresses, so a failure is told by its kind
 * ({@link RoundFailure}) and the exception's class name only (S12-AC-34).
 */
@Component
class ImapMailbox {

    private static final Logger log = LoggerFactory.getLogger(ImapMailbox.class);

    private final MailInSetup setup;

    ImapMailbox(MailInSetup setup) {
        this.setup = setup;
    }

    /** A round that cannot go on, and why (S12-AC-10). */
    static final class RoundFailure extends RuntimeException {

        private final FailureKind kind;

        RoundFailure(FailureKind kind, Exception cause) {
            super(kind + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")"), null, false, false);
            this.kind = kind;
        }

        FailureKind kind() {
            return kind;
        }
    }

    /** One mail of the incoming folder: its UID and {@code RFC822.SIZE}. */
    record Mail(long uid, long size) {}

    /** The bytes of one {@code FETCH} of a mail's raw bytes, as {@code mail.imap.fetchsize}. */
    static final int PART = Integer.parseInt(MailInSettings.FETCH_SIZE_BYTES);

    /** How the server did not give a section of a mail (B7-AC-15): a {@code NO}, an error, or no bytes. */
    enum Unread {
        NO,
        ERROR,
        EMPTY
    }

    /**
     * A mail as {@link Connection#read} read it: the email to handle, null when neither its raw bytes nor its header
     * came; and how its raw bytes and its header did not come, each null when it came or was not asked for.
     */
    record Read(Incoming incoming, Unread raw, Unread header) {

        /** Whether a section did not come, so the mail is skipped this round. */
        boolean skipped() {
            return raw != null || header != null;
        }
    }

    /** A section of a mail: its bytes, or how it did not come. */
    private record Section(byte[] bytes, Unread unread) {}

    /**
     * Signs in and opens the incoming folder for writing, after making the processed and quarantine folders when they
     * are missing. Fails with {@code CONNECT} when the server cannot be reached, {@code AUTH} when the sign-in is
     * refused, {@code UNSUPPORTED} when the server has neither {@code MOVE} nor {@code UIDPLUS}, {@code FOLDER} when
     * the incoming folder is missing or a folder cannot be made.
     */
    Connection open() {
        MailInSettings settings = setup.settings();
        Session session = Session.getInstance(settings.imapProperties());
        Store store;
        try {
            store = session.getStore("imap");
            store.connect(settings.host(), settings.port(), settings.user(), settings.password());
        } catch (AuthenticationFailedException e) {
            throw new RoundFailure(FailureKind.AUTH, e);
        } catch (MessagingException e) {
            throw new RoundFailure(FailureKind.CONNECT, e);
        }
        Connection connection = new Connection(store);
        try {
            connection.prepare(settings);
            return connection;
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** One round's connection. */
    static final class Connection implements AutoCloseable {

        private final Store store;
        private boolean move;
        private boolean uidPlus;
        private Folder inbound;
        private Folder processed;
        private Folder quarantine;

        private Connection(Store store) {
            this.store = store;
        }

        private void prepare(MailInSettings settings) {
            try {
                move = hasCapability(store, "MOVE");
                uidPlus = hasCapability(store, "UIDPLUS");
                if (!move && !uidPlus) {
                    throw new RoundFailure(FailureKind.UNSUPPORTED, null);
                }
                inbound = store.getFolder(settings.folder());
                if (!inbound.exists()) {
                    throw new RoundFailure(FailureKind.FOLDER, null);
                }
                processed = made(settings.processedFolder());
                quarantine = made(settings.quarantineFolder());
                inbound.open(Folder.READ_WRITE);
            } catch (MessagingException e) {
                throw new RoundFailure(failure(e), e);
            }
        }

        private Folder made(String name) throws MessagingException {
            Folder folder = store.getFolder(name);
            if (!folder.exists() && !folder.create(Folder.HOLDS_MESSAGES)) {
                throw new RoundFailure(FailureKind.FOLDER, null);
            }
            return folder;
        }

        /**
         * The first {@code max} mails of the incoming folder that are not marked deleted, in UID order, read or not
         * (S12-AC-06).
         */
        List<Mail> next(int max) {
            try {
                Message[] found = inbound.search(new FlagTerm(new Flags(Flags.Flag.DELETED), false));
                FetchProfile profile = new FetchProfile();
                profile.add(UIDFolder.FetchProfileItem.UID);
                profile.add(FetchProfile.Item.SIZE);
                inbound.fetch(found, profile);
                List<Mail> mails = new ArrayList<>();
                for (Message m : found) {
                    mails.add(new Mail(((UIDFolder) inbound).getUID(m), m.getSize()));
                }
                mails.sort((a, b) -> Long.compare(a.uid(), b.uid()));
                return mails.subList(0, Math.min(max, mails.size()));
            } catch (MessagingException e) {
                throw new RoundFailure(failure(e), e);
            }
        }

        /** The UIDVALIDITY of the incoming folder: with a UID, it names one mail across rounds. */
        long uidValidity() {
            try {
                return ((UIDFolder) inbound).getUIDValidity();
            } catch (MessagingException e) {
                throw new RoundFailure(failure(e), e);
            }
        }

        /**
         * The mail's raw bytes ({@code BODY.PEEK[]}, in parts of {@link #PART}); over {@code maxSize} only its header
         * block ({@code BODY.PEEK[HEADER]}), and {@code tooLarge}. When the server does not give the raw bytes while
         * the connection is up, the header block in their place (B7-AC-10, B7-AC-11); when it gives neither, no
         * email (B7-AC-13). Null when the mail is gone. A connection that broke fails the round (B7-AC-14).
         */
        Read read(Mail mail, long maxSize) {
            try {
                Message m = ((UIDFolder) inbound).getMessageByUID(mail.uid());
                if (m == null) {
                    return null;
                }
                if (mail.size() > maxSize) {
                    Section header = section(m, "HEADER", 0);
                    return new Read(header.bytes() == null ? null : new Incoming(header.bytes(), true, false), null,
                            header.unread());
                }
                Section raw = section(m, "", (int) Math.max(1024, mail.size()));
                if (raw.bytes() != null) {
                    return new Read(new Incoming(raw.bytes(), false, false), null, null);
                }
                Section header = section(m, "HEADER", 0);
                return new Read(header.bytes() == null ? null : new Incoming(header.bytes(), false, true),
                        raw.unread(), header.unread());
            } catch (MessagingException e) {
                throw new RoundFailure(FailureKind.CONNECT, e);
            }
        }

        /**
         * One section of the mail ({@code ""} for the raw bytes, {@code HEADER} for the header block), with
         * {@code BODY.PEEK}. The raw bytes come in parts of {@link #PART} until a shorter one, as the mail library
         * reads them; {@code expected} sizes their buffer, and 0 asks for the section whole in one request. A
         * {@code NO}, an error while the connection is up ({@code BAD} among them), or no bytes is an unread section;
         * an error with the connection gone is thrown.
         *
         * <p>The section is read here, not by {@code Message.writeTo}: the mail library takes a {@code NO} for a part
         * as the end of the mail and gives the bytes so far (b7 acceptance.md 1절).
         */
        private Section section(Message m, String section, int expected) throws MessagingException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, expected));
            try {
                if (expected == 0) {
                    byte[] all = body(m, section, -1);
                    if (all == null) {
                        return new Section(null, Unread.NO);
                    }
                    out.writeBytes(all);
                } else {
                    for (int start = 0;;) {
                        byte[] part = body(m, section, start);
                        if (part == null) {
                            return new Section(null, Unread.NO);
                        }
                        out.writeBytes(part);
                        if (part.length < PART) {
                            break;
                        }
                        start += part.length;
                    }
                }
            } catch (MessagingException e) {
                if (e instanceof FolderClosedException || e instanceof StoreClosedException || !up()) {
                    throw e;
                }
                return new Section(null, Unread.ERROR);
            }
            return out.size() == 0 ? new Section(null, Unread.EMPTY) : new Section(out.toByteArray(), null);
        }

        /**
         * One {@code FETCH} of the section ({@link #peekBody}): its bytes, empty when it has none, or null for a
         * {@code NO}.
         */
        private byte[] body(Message m, String section, int start) throws MessagingException {
            Object body = peekBody(inbound, m, section, start);
            if (body == null) {
                return null;
            }
            ByteArrayInputStream in = (ByteArrayInputStream) extension(body, "getByteArrayInputStream",
                    new Class<?>[0]);
            return in == null ? new byte[0] : in.readAllBytes();
        }

        /** Whether the connection is up: the store connected and the incoming folder open (b7 acceptance.md 2절). */
        private boolean up() {
            return inbound.isOpen() && store.isConnected();
        }

        /**
         * Moves the mail to the folder of its destination (S12-AC-07): {@code UID MOVE} with MOVE and UIDPLUS,
         * {@code MOVE} with MOVE alone (Angus sends {@code UID MOVE} only with UIDPLUS), else {@code COPY},
         * {@code \Deleted}, and {@code UID EXPUNGE}, which {@link #prepare} let through only with UIDPLUS.
         */
        void move(Mail mail, Destination destination) {
            Folder to = destination == Destination.QUARANTINE ? quarantine : processed;
            try {
                Message m = ((UIDFolder) inbound).getMessageByUID(mail.uid());
                if (m == null) {
                    return;
                }
                Message[] one = {m};
                if (move && uidPlus) {
                    extension(inbound, "moveUIDMessages", new Class<?>[] {Message[].class, Folder.class}, one, to);
                } else if (move) {
                    extension(inbound, "moveMessages", new Class<?>[] {Message[].class, Folder.class}, one, to);
                } else {
                    inbound.copyMessages(one, to);
                    inbound.setFlags(one, new Flags(Flags.Flag.DELETED), true);
                    extension(inbound, "expunge", new Class<?>[] {Message[].class}, (Object) one);
                }
            } catch (MessagingException e) {
                throw new RoundFailure(failure(e), e);
            }
        }

        /**
         * Deletes the mail of the processed and the quarantine folder that the server received before
         * {@code before} ({@code INTERNALDATE}): flagged {@code \Deleted}, then a {@code UID EXPUNGE} of those alone
         * (S12-AC-43). Without UIDPLUS nothing is deleted, and a warning names the two folders: the mail there is
         * for the administrator to delete.
         *
         * @return how many were deleted
         */
        int deleteReceivedBefore(Instant before) {
            int deleted = 0;
            try {
                if (!uidPlus) {
                    log.warn("The IMAP server does not offer UIDPLUS, so popdesk does not delete the mail older than 90 "
                            + "days from the folders {} and {}; delete it there yourself (docs/operate/privacy.md).",
                            processed.getFullName(), quarantine.getFullName());
                    return 0;
                }
                for (Folder folder : List.of(processed, quarantine)) {
                    folder.open(Folder.READ_WRITE);
                    try {
                        List<Message> old = new ArrayList<>();
                        // SEARCH BEFORE compares days; the time of each is compared here.
                        Collections.addAll(old, folder.search(new ReceivedDateTerm(ComparisonTerm.LT,
                                Date.from(before.plusSeconds(24 * 3600)))));
                        old.removeIf(m -> {
                            try {
                                Date received = m.getReceivedDate();
                                return received == null || !received.toInstant().isBefore(before)
                                        || m.isSet(Flags.Flag.DELETED);
                            } catch (MessagingException e) {
                                throw new RoundFailure(failure(e), e);
                            }
                        });
                        if (!old.isEmpty()) {
                            Message[] all = old.toArray(Message[]::new);
                            folder.setFlags(all, new Flags(Flags.Flag.DELETED), true);
                            extension(folder, "expunge", new Class<?>[] {Message[].class}, (Object) all);
                            deleted += all.length;
                        }
                    } finally {
                        folder.close(false);
                    }
                }
                return deleted;
            } catch (MessagingException e) {
                throw new RoundFailure(failure(e), e);
            }
        }

        @Override
        public void close() {
            try {
                if (inbound != null && inbound.isOpen()) {
                    inbound.close(false);
                }
            } catch (MessagingException e) {
                // The connection is going anyway; the next round opens a new one.
                inbound = null;
            } finally {
                try {
                    store.close();
                } catch (MessagingException e) {
                    // As above.
                    inbound = null;
                }
            }
        }
    }

    /**
     * {@code IMAPProtocol.peekBody} of the message's section through {@code IMAPFolder.doCommand}, which holds the
     * folder's lock and turns a broken connection into {@link FolderClosedException}: from {@code start} for at most
     * {@link #PART} bytes, or whole for a negative {@code start}. The message is named by its sequence number on the
     * server as the folder keeps it, which a move earlier in the round changes ({@code IMAPMessage.getSequenceNumber},
     * as the mail library's own reading does; {@code Message.getMessageNumber} is not it). The command is a proxy of
     * {@code IMAPFolder.ProtocolCommand}: the provider is not there when this is compiled ({@link #extension}).
     *
     * @return the {@code BODY} item, or null for a {@code NO}
     */
    private static Object peekBody(Folder folder, Message message, String section, int start)
            throws MessagingException {
        ClassLoader loader = folder.getClass().getClassLoader();
        Class<?> command;
        Method sequenceNumber;
        try {
            command = Class.forName("org.eclipse.angus.mail.imap.IMAPFolder$ProtocolCommand", false, loader);
            sequenceNumber = Class.forName("org.eclipse.angus.mail.imap.IMAPMessage", false, loader)
                    .getDeclaredMethod("getSequenceNumber");
            sequenceNumber.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The IMAP provider has no ProtocolCommand or sequence number", e);
        }
        Object proxy = Proxy.newProxyInstance(command.getClassLoader(), new Class<?>[] {command}, (self, m, a) -> {
            if (!m.getName().equals("doCommand")) {
                throw new UnsupportedOperationException(m.getName());
            }
            try {
                int number = (Integer) sequenceNumber.invoke(message);
                return start < 0
                        ? a[0].getClass().getMethod("peekBody", int.class, String.class).invoke(a[0], number, section)
                        : a[0].getClass().getMethod("peekBody", int.class, String.class, int.class, int.class)
                                .invoke(a[0], number, section, start, PART);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        return extension(folder, "doCommand", new Class<?>[] {command}, proxy);
    }

    /** Whether the IMAP server named the capability (Angus {@code IMAPStore.hasCapability}). */
    private static boolean hasCapability(Store store, String capability) throws MessagingException {
        return Boolean.TRUE.equals(extension(store, "hasCapability", new Class<?>[] {String.class}, capability));
    }

    /**
     * Calls a method of the IMAP provider that the Jakarta Mail API has no name for: {@code hasCapability},
     * {@code moveUIDMessages} (UID MOVE), {@code moveMessages} (MOVE), {@code expunge(Message[])} (UID EXPUNGE). The
     * provider (Eclipse Angus) is there when the app runs, not when it is compiled.
     */
    private static Object extension(Object target, String method, Class<?>[] types, Object... args)
            throws MessagingException {
        try {
            return target.getClass().getMethod(method, types).invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof MessagingException m) {
                throw m;
            }
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException("The IMAP provider failed in " + method, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The IMAP provider has no " + method, e);
        }
    }

    /** {@code AUTH} for a refused sign-in, else {@code CONNECT}: the connection broke or timed out. */
    static FailureKind failure(MessagingException e) {
        return e instanceof AuthenticationFailedException ? FailureKind.AUTH : FailureKind.CONNECT;
    }
}
