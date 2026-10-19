package io.popdesk.helpdesk.mailin;

import io.popdesk.helpdesk.mailin.MailInProcessor.Destination;
import io.popdesk.helpdesk.mailin.MailInProcessor.Incoming;
import io.popdesk.notification.MailInState.FailureKind;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
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

        /**
         * The mail's raw bytes ({@code BODY.PEEK[]}); over {@code maxSize} only its header block, and
         * {@code tooLarge}. Empty when the mail is gone.
         */
        Incoming read(Mail mail, long maxSize) {
            try {
                Message m = ((UIDFolder) inbound).getMessageByUID(mail.uid());
                if (m == null) {
                    return null;
                }
                if (mail.size() > maxSize) {
                    StringBuilder header = new StringBuilder();
                    for (Enumeration<String> lines = ((jakarta.mail.internet.MimeMessage) m).getAllHeaderLines();
                            lines.hasMoreElements();) {
                        header.append(lines.nextElement()).append("\r\n");
                    }
                    header.append("\r\n");
                    return new Incoming(header.toString().getBytes(StandardCharsets.UTF_8), true);
                }
                ByteArrayOutputStream raw = new ByteArrayOutputStream((int) Math.max(1024, mail.size()));
                m.writeTo(raw);
                return new Incoming(raw.toByteArray(), false);
            } catch (MessagingException | IOException e) {
                throw new RoundFailure(FailureKind.CONNECT, e);
            }
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
