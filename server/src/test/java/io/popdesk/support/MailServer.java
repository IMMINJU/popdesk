package io.popdesk.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Real SMTP servers inside the test JVM (GreenMail), one set for the whole JVM ({@link #shared()}).
 *
 * <ul>
 *   <li>{@link #plainPort()}: plain SMTP without STARTTLS. The app reaches it only with
 *       {@code SMTP_ALLOW_PLAINTEXT=true}; without it, the app must refuse to send.
 *   <li>{@link #startTlsPort()}: offers STARTTLS and refuses mail until the client switched to TLS.
 *       The JVM trusts its certificate ({@link TestTls}).
 * </ul>
 *
 * Both deliver into the same GreenMail, which accepts {@link #USER}/{@link #PASSWORD} and stores mail
 * for any recipient. Mail from earlier tests or other contexts may be there; find yours by its
 * recipient, which fixtures make unique.
 */
public final class MailServer {

    public static final String HOST = "127.0.0.1";
    public static final String USER = "popdesk";
    public static final String PASSWORD = "smtp-secret";
    public static final String FROM = "popdesk@popdesk.test";

    private static MailServer shared;

    private final GreenMail greenMail;
    private final StartTlsProxy startTls;

    private MailServer() {
        TestTls.trustTestCertificate();
        greenMail = new GreenMail(new ServerSetup(0, HOST, ServerSetup.PROTOCOL_SMTP))
                .withConfiguration(GreenMailConfiguration.aConfig().withUser(USER, PASSWORD));
        greenMail.start();
        startTls = new StartTlsProxy(greenMail.getSmtp().getPort(), TestTls.serverContext());
    }

    /** The servers for this JVM, started on first use. */
    public static synchronized MailServer shared() {
        if (shared == null) {
            shared = new MailServer();
        }
        return shared;
    }

    public int plainPort() {
        return greenMail.getSmtp().getPort();
    }

    public int startTlsPort() {
        return startTls.port();
    }

    /** Connections to the STARTTLS port that switched to TLS. */
    public int startTlsConnections() {
        return startTls.upgradedConnections();
    }

    /** Commands the STARTTLS port refused because the client stayed on plain text. */
    public int plainTextRefusals() {
        return startTls.refusedCommands();
    }

    /** All mail received so far, oldest first. */
    public List<ReceivedMail> messages() {
        return Arrays.stream(greenMail.getReceivedMessages()).map(MailServer::read).toList();
    }

    /** Mail to {@code address} (ignoring case). */
    public List<ReceivedMail> messagesTo(String address) {
        return messages().stream().filter(m -> m.isTo(address)).toList();
    }

    /** Waits for the first mail to {@code address}; fails with an AssertionError when none comes. */
    public ReceivedMail awaitMessageTo(String address) {
        return awaitMessage("mail to " + address, m -> m.isTo(address));
    }

    public ReceivedMail awaitMessage(String what, Predicate<ReceivedMail> filter) {
        return Wait.until(what, () -> messages().stream().filter(filter).findFirst().orElse(null));
    }

    /** Deletes all received mail. */
    public void reset() {
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (Exception e) {
            throw new IllegalStateException("Could not clear GreenMail", e);
        }
    }

    private static ReceivedMail read(MimeMessage message) {
        try {
            return ReceivedMail.of(message);
        } catch (MessagingException e) {
            throw new AssertionError("Could not read a received mail", e);
        }
    }
}
