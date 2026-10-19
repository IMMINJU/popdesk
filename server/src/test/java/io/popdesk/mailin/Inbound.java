package io.popdesk.mailin;

import io.popdesk.support.MailInServer;
import io.popdesk.support.Unique;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A mail to put in the test mailbox, written out as raw RFC 5322 bytes by hand, so a test controls every header: two
 * {@code From} headers, a folded header, a missing {@code Message-ID} are all just lines. Nothing here calls
 * {@code saveChanges}, so no library adds or changes a header.
 *
 * <p>Starts with {@code To: MAIL_INBOUND_ADDRESS}, a {@code Date}, a new {@code Message-ID} of the form
 * {@code <t-…@mail.example>}, and a unique subject; it has no {@code From} until the test sets one. Text parts and
 * files go out in base64. With files, or with both a text and an HTML body, the mail is multipart.
 */
public final class Inbound {

    private static final String CRLF = "\r\n";

    private record Header(String name, String value) {}

    private record Part(String contentType, String disposition, String fileName, byte[] content, Inbound message) {}

    private final List<String> from = new ArrayList<>();
    private final List<String> to = new ArrayList<>(List.of(MailInServer.ADDRESS));
    private final List<String> cc = new ArrayList<>();
    private String subject = Unique.text("Mail");
    private boolean rawSubject;
    private String messageId = newMessageId();
    private String inReplyTo;
    private String references;
    private final List<Header> headers = new ArrayList<>();
    private String text;
    private String html;
    private String charset = "UTF-8";
    private String topType;
    private final List<Part> parts = new ArrayList<>();

    private Inbound() {}

    /** A new mail with the defaults above. */
    public static Inbound mail() {
        return new Inbound();
    }

    /** A {@code Message-ID} no other mail has, with its angle brackets: {@code <t-12-a1b2c3d4@mail.example>}. */
    public static String newMessageId() {
        return "<t-" + Unique.number() + "-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt())
                + "@mail.example>";
    }

    /** Raw mail written by hand, with {@code \n} line ends turned into CRLF. */
    public static byte[] raw(String text) {
        return text.replace("\r\n", "\n").replace("\n", CRLF).getBytes(StandardCharsets.UTF_8);
    }

    // Headers

    /** A {@code From} header with this value as written ({@code a@b} or {@code "Name" <a@b>}). Twice gives two headers. */
    public Inbound from(String value) {
        from.add(value);
        return this;
    }

    /** Replaces the {@code To} addresses (header values as written, joined with commas). */
    public Inbound to(String... values) {
        to.clear();
        to.addAll(List.of(values));
        return this;
    }

    /** Leaves out the {@code To} header. */
    public Inbound noTo() {
        to.clear();
        return this;
    }

    public Inbound cc(String... values) {
        cc.addAll(List.of(values));
        return this;
    }

    /** The subject; non-ASCII text, line breaks, and control characters go out as RFC 2047 encoded words. */
    public Inbound subject(String subject) {
        this.subject = subject;
        this.rawSubject = false;
        return this;
    }

    /** The {@code Subject} header value exactly as written (for a test of the encoded form itself). */
    public Inbound rawSubject(String value) {
        this.subject = value;
        this.rawSubject = true;
        return this;
    }

    /** Leaves out the {@code Subject} header. */
    public Inbound noSubject() {
        this.subject = null;
        return this;
    }

    public String subject() {
        return subject;
    }

    /** The {@code Message-ID} header value, with its angle brackets. */
    public Inbound messageId(String value) {
        this.messageId = value;
        return this;
    }

    /** Leaves out the {@code Message-ID} header. */
    public Inbound noMessageId() {
        this.messageId = null;
        return this;
    }

    /** The {@code Message-ID} value, or null without one. */
    public String messageId() {
        return messageId;
    }

    public Inbound inReplyTo(String value) {
        this.inReplyTo = value;
        return this;
    }

    /** The {@code References} header: the values joined with spaces. */
    public Inbound references(String... values) {
        this.references = String.join(" ", values);
        return this;
    }

    /** Any other header, after the ones above, in the order added. A value may hold CRLF and a space (folded). */
    public Inbound header(String name, String value) {
        headers.add(new Header(name, value));
        return this;
    }

    // Body

    /** The {@code text/plain} body. */
    public Inbound text(String text) {
        this.text = text;
        return this;
    }

    /** The {@code text/html} body. */
    public Inbound html(String html) {
        this.html = html;
        return this;
    }

    /** The charset parameter of the text parts; the bytes stay UTF-8 ({@code x-bogus} for a broken name). */
    public Inbound charset(String charset) {
        this.charset = charset;
        return this;
    }

    /** The top {@code Content-Type} of a multipart mail ({@code multipart/report; report-type=delivery-status}). */
    public Inbound topType(String contentType) {
        this.topType = contentType;
        return this;
    }

    /** A file as {@code Content-Disposition: attachment} with a file name. */
    public Inbound attach(String fileName, String contentType, byte[] content) {
        parts.add(new Part(contentType, "attachment", fileName, content, null));
        return this;
    }

    /** A file as {@code Content-Disposition: inline} with a file name (a picture in the body). */
    public Inbound inline(String fileName, String contentType, byte[] content) {
        parts.add(new Part(contentType, "inline", fileName, content, null));
        return this;
    }

    /** A part with no disposition and no name. */
    public Inbound unnamed(String contentType, byte[] content) {
        parts.add(new Part(contentType, null, null, content, null));
        return this;
    }

    /** Another mail attached as {@code message/rfc822}, without a file name (a forwarded mail). */
    public Inbound attachMessage(Inbound message) {
        parts.add(new Part("message/rfc822", "attachment", null, null, message));
        return this;
    }

    // Out

    /** The mail as raw bytes. */
    public byte[] bytes() {
        Out out = new Out();
        for (String f : from) {
            out.header("From", f);
        }
        if (!to.isEmpty()) {
            out.header("To", String.join(", ", to));
        }
        if (!cc.isEmpty()) {
            out.header("Cc", String.join(", ", cc));
        }
        if (subject != null) {
            out.header("Subject", rawSubject ? subject : encodeText(subject));
        }
        out.header("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));
        if (messageId != null) {
            out.header("Message-ID", messageId);
        }
        if (inReplyTo != null) {
            out.header("In-Reply-To", inReplyTo);
        }
        if (references != null) {
            out.header("References", references);
        }
        for (Header h : headers) {
            out.header(h.name(), h.value());
        }
        out.header("MIME-Version", "1.0");
        writeBody(out);
        return out.bytes();
    }

    private void writeBody(Out out) {
        boolean alternative = text != null && html != null;
        if (parts.isEmpty() && topType == null) {
            if (alternative) {
                writeAlternative(out);
            } else if (html != null) {
                writeText(out, "text/html", html);
            } else {
                writeText(out, "text/plain", text == null ? "" : text);
            }
            return;
        }
        String boundary = boundary();
        out.header("Content-Type", (topType == null ? "multipart/mixed" : topType) + "; boundary=\"" + boundary + "\"");
        out.line("");
        if (alternative) {
            out.line("--" + boundary);
            writeAlternative(out);
        } else if (text != null || html != null) {
            out.line("--" + boundary);
            writeText(out, text != null ? "text/plain" : "text/html", text != null ? text : html);
        }
        for (Part p : parts) {
            out.line("--" + boundary);
            writePart(out, p);
        }
        out.line("--" + boundary + "--");
    }

    private void writeAlternative(Out out) {
        String boundary = boundary();
        out.header("Content-Type", "multipart/alternative; boundary=\"" + boundary + "\"");
        out.line("");
        out.line("--" + boundary);
        writeText(out, "text/plain", text);
        out.line("--" + boundary);
        writeText(out, "text/html", html);
        out.line("--" + boundary + "--");
    }

    private void writeText(Out out, String type, String body) {
        out.header("Content-Type", type + "; charset=" + charset);
        out.header("Content-Transfer-Encoding", "base64");
        out.line("");
        out.base64(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writePart(Out out, Part p) {
        if (p.message() != null) {
            out.header("Content-Type", "message/rfc822");
            out.header("Content-Disposition", "attachment");
            out.line("");
            out.raw(p.message().bytes());
            out.line("");
            return;
        }
        String nameParam = p.fileName() == null ? "" : "; " + fileNameParam("name", p.fileName());
        out.header("Content-Type", p.contentType() + nameParam);
        if (p.disposition() != null) {
            String fileParam = p.fileName() == null ? "" : "; " + fileNameParam("filename", p.fileName());
            out.header("Content-Disposition", p.disposition() + fileParam);
        }
        out.header("Content-Transfer-Encoding", "base64");
        out.line("");
        out.base64(p.content());
    }

    /**
     * {@code name="a.txt"} for a plain name, else the RFC 2231 form {@code name*=UTF-8''%EB%B3%B4…}, which also carries
     * a backslash, a line break, or a bidi control character as it is.
     */
    static String fileNameParam(String param, String fileName) {
        if (fileName.matches("[A-Za-z0-9._ -]*")) {
            return param + "=\"" + fileName + "\"";
        }
        StringBuilder pct = new StringBuilder();
        for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-'
                    || c == '_') {
                pct.append((char) c);
            } else {
                pct.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return param + "*=UTF-8''" + pct;
    }

    private static String encodeText(String value) {
        if (value.matches("[\\x20-\\x7e]*")) {
            return value;
        }
        try {
            return MimeUtility.encodeText(value, "UTF-8", "B");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String boundary() {
        return "=_t" + Unique.number() + "_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    /** The bytes being written, line by line. */
    private static final class Out {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        void header(String name, String value) {
            line(name + ": " + value);
        }

        void line(String line) {
            raw(line.getBytes(StandardCharsets.UTF_8));
            raw(CRLF.getBytes(StandardCharsets.US_ASCII));
        }

        void raw(byte[] b) {
            bytes.write(b, 0, b.length);
        }

        void base64(byte[] content) {
            raw(Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII)).encode(content));
            raw(CRLF.getBytes(StandardCharsets.US_ASCII));
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
