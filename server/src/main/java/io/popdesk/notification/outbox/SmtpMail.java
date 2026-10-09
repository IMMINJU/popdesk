package io.popdesk.notification.outbox;

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
import java.time.Clock;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import javax.net.ssl.SSLException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The SMTP connection (D-51, S0-AC-74): a new connection for each email. Port 465 is TLS from the first
 * byte; any other port must switch to TLS with STARTTLS, and a server that does not offer it gets no
 * email, unless {@code SMTP_ALLOW_PLAINTEXT=true}. The server's certificate and host name are checked
 * against the JVM's trust store. The settings are read from the environment for each email.
 */
@Component
class SmtpMail {

    static final int IMPLICIT_TLS_PORT = 465;
    static final int DEFAULT_PORT = 587;

    private static final String CONNECT_TIMEOUT_MILLIS = "10000";
    private static final String TIMEOUT_MILLIS = "30000";

    private final Environment environment;
    private final Clock clock;

    SmtpMail(Environment environment, Clock clock) {
        this.environment = environment;
        this.clock = clock;
    }

    /** Whether outgoing email is configured ({@code SMTP_HOST}). */
    boolean enabled() {
        return setting("SMTP_HOST") != null;
    }

    /** Whether plain text is allowed ({@code SMTP_ALLOW_PLAINTEXT=true}). */
    boolean plainTextAllowed() {
        return "true".equalsIgnoreCase(setting("SMTP_ALLOW_PLAINTEXT"));
    }

    /** Sends one email to one recipient, with no Cc or Bcc (S0-AC-71). */
    Attempt send(String to, Message message) {
        String host = setting("SMTP_HOST");
        String portSetting = setting("SMTP_PORT");
        int port;
        try {
            port = portSetting == null ? DEFAULT_PORT : Integer.parseInt(portSetting);
        } catch (NumberFormatException e) {
            return Attempt.failed("SMTP_PORT_INVALID");
        }
        String user = setting("SMTP_USER");
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
                Transport.send(mime, user, setting("SMTP_PASSWORD"));
            }
            return Attempt.sent();
        } catch (MessagingException e) {
            return Attempt.failed(kind(e));
        }
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

    private String setting(String key) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
