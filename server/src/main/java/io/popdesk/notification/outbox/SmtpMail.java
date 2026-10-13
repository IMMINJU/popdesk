package io.popdesk.notification.outbox;

import io.popdesk.notification.Sandbox;
import io.popdesk.notification.outbox.MailText.Message;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The SMTP connection (D-51, S0-AC-74): a new connection for each email. Port 465 is TLS from the first
 * byte; any other port must switch to TLS with STARTTLS, and a server that does not offer it gets no
 * email, unless {@code SMTP_ALLOW_PLAINTEXT=true}. The server's certificate and host name are checked
 * against the JVM's trust store. The settings are read from the environment for each email.
 *
 * <p>Every email popdesk sends leaves through {@link #deliver}, and two things happen only there. In a
 * {@link Sandbox} the email goes to the sandbox address in place of its recipient's (S4B-AC-01), with
 * {@code [sandbox]} before the subject and the recipient's account ID as the first line of the text
 * (S4B-AC-02); whether the email is to be sent at all was decided before, for the recipient it was
 * meant for. And an address that had its 50 emails this hour gets no more ({@link MailLimit}), counted
 * by the address the email really goes to.
 */
@Component
class SmtpMail {

    static final int IMPLICIT_TLS_PORT = 465;
    static final int DEFAULT_PORT = 587;

    static final String SANDBOX_SUBJECT_PREFIX = "[sandbox] ";

    /** The longest server reply {@link #sendForReply} gives. */
    static final int REPLY_MAX = 500;

    private static final String CONNECT_TIMEOUT_MILLIS = "10000";
    private static final String TIMEOUT_MILLIS = "30000";

    /**
     * The exceptions of the mail library (Eclipse Angus) whose message is the reply of the SMTP server
     * to a refused address and to a refused message. By name: the library's own classes are there when
     * the app runs, not when it is compiled.
     */
    private static final Set<String> CARRY_A_REPLY = Set.of("SMTPAddressFailedException", "SMTPSendFailedException");

    /** What a secret leaves behind in a server reply. */
    private static final String REDACTED = "***";

    /** One email as it came out: the attempt, and what the SMTP server said when it refused, else null. */
    record Result(Attempt attempt, String serverReply) {}

    private final Environment environment;
    private final Sandbox sandbox;
    private final MailLimit limit;
    private final Clock clock;

    SmtpMail(Environment environment, Sandbox sandbox, MailLimit limit, Clock clock) {
        this.environment = environment;
        this.sandbox = sandbox;
        this.limit = limit;
        this.clock = clock;
    }

    /**
     * Whether email is sent at all: {@code SMTP_HOST} is set, and this is no sandbox without an address
     * (S4B-AC-03).
     */
    boolean enabled() {
        return setting("SMTP_HOST") != null && !sandbox.mailOff();
    }

    /** Whether plain text is allowed ({@code SMTP_ALLOW_PLAINTEXT=true}). */
    boolean plainTextAllowed() {
        return "true".equalsIgnoreCase(setting("SMTP_ALLOW_PLAINTEXT"));
    }

    /**
     * Sends one email to one recipient, with no Cc or Bcc (S0-AC-71). The send loop's way: what the
     * server said stays here, so it reaches neither the log nor the outbox row.
     *
     * @param recipientAccountId the account the email is for
     * @param to that account's address
     */
    Attempt send(long recipientAccountId, String to, Message message) {
        return deliver(recipientAccountId, to, message).attempt();
    }

    /**
     * Sends one email as {@link #send} does and also gives what the SMTP server answered when it
     * refused, for the test email (S4B-AC-23, S4B-AC-24): at most {@value #REPLY_MAX} characters, line
     * breaks but no other control characters, without the SMTP user name and password, and with the
     * address the email went to masked. Null when the email went out and when the server gave no
     * answer: what the mail library says of a connection that failed names the SMTP host and port.
     */
    Result sendForReply(long recipientAccountId, String to, Message message) {
        return deliver(recipientAccountId, to, message);
    }

    private Result deliver(long recipientAccountId, String recipient, Message original) {
        if (sandbox.mailOff()) {
            return new Result(Attempt.dropped(), null);
        }
        String to = sandbox.mailTo().orElse(recipient);
        Message message = sandbox.on() ? redirected(original, recipientAccountId) : original;
        if (limit.stops(to)) {
            return new Result(Attempt.stopped(MailLimit.ERROR_KIND), null);
        }
        String host = setting("SMTP_HOST");
        String portSetting = setting("SMTP_PORT");
        int port;
        try {
            port = portSetting == null ? DEFAULT_PORT : Integer.parseInt(portSetting);
        } catch (NumberFormatException e) {
            return new Result(Attempt.failed("SMTP_PORT_INVALID"), null);
        }
        String user = setting("SMTP_USER");
        String password = setting("SMTP_PASSWORD");
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", host);
        properties.setProperty("mail.smtp.port", Integer.toString(port));
        properties.setProperty("mail.smtp.auth", Boolean.toString(user != null));
        properties.setProperty("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MILLIS);
        properties.setProperty("mail.smtp.timeout", TIMEOUT_MILLIS);
        properties.setProperty("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        if (port == IMPLICIT_TLS_PORT) {
            properties.setProperty("mail.smtp.ssl.enable", "true");
        } else {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", Boolean.toString(!plainTextAllowed()));
        }
        Session session = Session.getInstance(properties);
        try {
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(from(user)));
            mime.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to, true));
            mime.setSubject(message.subject(), "UTF-8");
            mime.setText(message.text(), "UTF-8");
            mime.setSentDate(Date.from(clock.instant()));
            if (user == null) {
                Transport.send(mime);
            } else {
                Transport.send(mime, user, password);
            }
            limit.sent(to);
            return new Result(Attempt.sent(), null);
        } catch (MessagingException e) {
            return new Result(Attempt.failed(kind(e)), serverReply(e, user, password, to));
        }
    }

    /**
     * The email as a sandbox sends it: marked in the subject, and the account it was for on a first line
     * of its own. The account's ID only, never its address (S4B-AC-02).
     */
    static Message redirected(Message message, long recipientAccountId) {
        return new Message(SANDBOX_SUBJECT_PREFIX + message.subject(),
                "Sandbox: this email was for account " + recipientAccountId + ".\n\n" + message.text());
    }

    /** {@code MAIL_FROM}, else {@code SMTP_USER} when it is an address, else popdesk at the console host. */
    private String from(String user) {
        String from = setting("MAIL_FROM");
        if (from != null) {
            return from;
        }
        if (user != null && user.contains("@")) {
            return user;
        }
        String host = setting("POPDESK_CONSOLE_HOST");
        return "popdesk@" + (host == null ? "localhost" : host);
    }

    /** A short name for what went wrong, never the exception's message: it can hold an address. */
    static String kind(MessagingException e) {
        if (e instanceof AuthenticationFailedException) {
            return "SMTP_AUTHENTICATION_FAILED";
        }
        if (e instanceof SendFailedException) {
            return "SMTP_REFUSED";
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return "TLS_FAILED";
            }
            if (t instanceof SocketTimeoutException) {
                return "TIMEOUT";
            }
            if (t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof UnknownHostException) {
                return "CONNECTION_FAILED";
            }
        }
        String message = e.getMessage();
        if (message != null && message.toUpperCase(Locale.ROOT).contains("STARTTLS")) {
            return "STARTTLS_NOT_OFFERED";
        }
        return "SMTP_ERROR";
    }

    /**
     * What the SMTP server said, from the exceptions that carry a reply of the server: a refused
     * sign-in, a refused address, a refused message. Any other failure has no reply.
     */
    static String serverReply(MessagingException e, String user, String password, String to) {
        String said = e instanceof AuthenticationFailedException ? e.getMessage() : null;
        for (Exception x = e; said == null && x != null;
                x = x instanceof MessagingException m ? m.getNextException() : null) {
            if (CARRY_A_REPLY.contains(x.getClass().getSimpleName())) {
                said = x.getMessage();
            }
        }
        return said == null ? null : cleaned(said, user, password, to);
    }

    /**
     * A server reply made safe to show (S4B-AC-24). A server may repeat what it was sent, so the user
     * name and the password are taken out, as they are and as the Base64 texts an SMTP sign-in sends,
     * and the address is masked. Control characters other than the line break go, and the rest is cut
     * at {@value #REPLY_MAX} characters.
     */
    static String cleaned(String reply, String user, String password, String to) {
        String text = reply;
        for (String secret : secrets(user, password)) {
            text = replaceIgnoringCase(text, secret, REDACTED);
        }
        text = replaceIgnoringCase(text, to, MailSender.masked(to));
        // \p{Cc}, not \p{Cntrl}: the second is ASCII only and would leave U+0080 to U+009F.
        text = text.replace("\r\n", "\n").replaceAll("[\\p{Cc}&&[^\n]]", "").strip();
        return text.length() > REPLY_MAX ? text.substring(0, REPLY_MAX) : text;
    }

    /** The forms a server could repeat the sign-in in, the longest first so that no part of one is left. */
    private static List<String> secrets(String user, String password) {
        List<String> plain = new ArrayList<>();
        if (user != null) {
            plain.add(user);
        }
        if (password != null) {
            plain.add(password);
        }
        List<String> forms = new ArrayList<>(plain);
        if (user != null && password != null) {
            // AUTH PLAIN sends both in one Base64 text.
            plain.add("\0" + user + "\0" + password);
        }
        for (String value : plain) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            forms.add(Base64.getEncoder().encodeToString(bytes));
            forms.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
        }
        forms.sort(Comparator.comparingInt(String::length).reversed());
        return forms;
    }

    private static String replaceIgnoringCase(String text, String what, String with) {
        String lower = text.toLowerCase(Locale.ROOT);
        String find = what.toLowerCase(Locale.ROOT);
        if (find.isEmpty() || lower.length() != text.length() || find.length() != what.length()) {
            return text.replace(what, with);
        }
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        for (int at = lower.indexOf(find); at >= 0; at = lower.indexOf(find, from)) {
            out.append(text, from, at).append(with);
            from = at + find.length();
        }
        return out.append(text, from, text.length()).toString();
    }

    private String setting(String key) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
