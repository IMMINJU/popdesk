package io.popdesk.helpdesk.mailin;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One received email, read from its raw bytes without the IMAP server: its headers, its key (acceptance.md 2절), its
 * body, and its named parts. Reading it reaches for nothing outside the bytes; only {@link #files} writes temporary
 * files.
 */
final class MailMessage {

    /** The most MIME parts and the deepest nesting an email may have (S12-AC-09). */
    static final int MAX_PARTS = 200;
    static final int MAX_DEPTH = 10;

    /** The most {@code Message-ID}s of {@code In-Reply-To} and {@code References} looked up (S12-AC-15). */
    static final int MAX_REFERENCES = 50;

    /** The longest issue title (ISSUE-2). */
    static final int TITLE_MAX = 200;

    /** C0 and C1 controls, and the line and paragraph separators. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f\\u2028\\u2029]");

    /** Bidirectional controls (U+202A to U+202E, U+2066 to U+2069). */
    private static final Pattern BIDI = Pattern.compile("[\\u202a-\\u202e\\u2066-\\u2069]");

    private static final Pattern ANGLED = Pattern.compile("<([^<>]*)>");

    /** The key of a received email and what it was made from. */
    record Key(String value, String source) {}

    /** A part of the email with a file name: the name as sent, decoded, and the part. */
    record NamedPart(String name, String contentType, Part part) {}

    private static final Session SESSION = Session.getInstance(new Properties());

    private final byte[] raw;
    private final MimeMessage mime;

    private MailMessage(byte[] raw, MimeMessage mime) {
        this.raw = raw;
        this.mime = mime;
    }

    /** The email of these raw bytes, as the IMAP server gave them ({@code BODY[]}), or only its header block. */
    static MailMessage of(byte[] raw) {
        try {
            return new MailMessage(raw, new MimeMessage(SESSION, new ByteArrayInputStream(raw)));
        } catch (MessagingException e) {
            throw new IllegalStateException("A received email could not be read (" + e.getClass().getSimpleName()
                    + ")");
        }
    }

    /** The raw values of the header, possibly folded; empty when the email has none. */
    List<String> header(String name) {
        try {
            String[] values = mime.getHeader(name);
            return values == null ? List.of() : Arrays.asList(values);
        } catch (MessagingException e) {
            return List.of();
        }
    }

    /** The email's own {@code Content-Type}, or null without one. */
    String topContentType() {
        List<String> values = header("Content-Type");
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * The one address of {@code From} (addr-spec, as written), or null when there is not exactly one {@code From}
     * header with exactly one address (S12-AC-13). A display name, comments, and encoded words are not read.
     */
    String fromAddress() {
        List<String> from = header("From");
        if (from.size() != 1) {
            return null;
        }
        List<String> addresses = addresses(from.get(0));
        return addresses.size() == 1 ? addresses.get(0) : null;
    }

    /** The addresses of every {@code To} and then every {@code Cc} header, in their order. */
    List<String> toAndCc() {
        List<String> out = new ArrayList<>();
        for (String name : List.of("To", "Cc")) {
            for (String value : header(name)) {
                out.addAll(addresses(value));
            }
        }
        return out;
    }

    /** The addr-specs of an address header; none when it cannot be read. */
    private static List<String> addresses(String value) {
        try {
            List<String> out = new ArrayList<>();
            for (InternetAddress a : InternetAddress.parseHeader(AutomatedMail.unfold(value), false)) {
                if (a.getAddress() != null && !a.isGroup()) {
                    out.add(a.getAddress());
                }
            }
            return out;
        } catch (AddressException e) {
            return List.of();
        }
    }

    /** The {@code Message-ID} without its angle brackets and the spaces around them, or null without one. */
    String messageId() {
        List<String> values = header("Message-ID");
        if (values.size() != 1) {
            return null;
        }
        String id = AutomatedMail.unfold(values.get(0)).strip();
        if (id.startsWith("<")) {
            id = id.substring(1);
        }
        if (id.endsWith(">")) {
            id = id.substring(0, id.length() - 1);
        }
        id = id.strip();
        return id.isEmpty() ? null : id;
    }

    /**
     * The key of the email (acceptance.md 2절): with one {@code From} address and a {@code Message-ID}, the SHA-256 of
     * the address in lower case, a line break, and the {@code Message-ID} without its angle brackets; without the
     * {@code Message-ID}, of the address, a line break, and the SHA-256 of the raw bytes as hex; without one
     * {@code From} address, the SHA-256 of the raw bytes. As lower-case hex.
     */
    Key key() {
        String from = fromAddress();
        if (from == null) {
            return new Key(hex(sha256(raw)), "CONTENT");
        }
        String id = messageId();
        String address = from.toLowerCase(Locale.ROOT);
        if (id == null) {
            return new Key(hex(sha256((address + "\n" + hex(sha256(raw))).getBytes(StandardCharsets.UTF_8))),
                    "CONTENT");
        }
        return new Key(hex(sha256((address + "\n" + id).getBytes(StandardCharsets.UTF_8))), "MESSAGE_ID");
    }

    /**
     * The {@code Message-ID}s of {@code References} and then {@code In-Reply-To}, without angle brackets, each once
     * at its last place, the last {@value #MAX_REFERENCES} of them; the latest is last.
     */
    List<String> references() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String name : List.of("References", "In-Reply-To")) {
            for (String value : header(name)) {
                Matcher m = ANGLED.matcher(AutomatedMail.unfold(value));
                boolean any = false;
                while (m.find()) {
                    any = true;
                    add(ids, m.group(1));
                }
                if (!any) {
                    for (String token : AutomatedMail.unfold(value).strip().split("\\s+")) {
                        add(ids, token);
                    }
                }
            }
        }
        List<String> all = new ArrayList<>(ids);
        return all.subList(Math.max(0, all.size() - MAX_REFERENCES), all.size());
    }

    private static void add(LinkedHashSet<String> ids, String id) {
        String bare = id.strip();
        if (!bare.isEmpty()) {
            ids.remove(bare);
            ids.add(bare);
        }
    }

    /**
     * The subject as an issue title (S12-AC-20): encoded words decoded, NFC, each control character and line or
     * paragraph separator a space, trimmed, the first {@value #TITLE_MAX} code points. Empty without a subject.
     */
    String title() {
        String subject;
        try {
            subject = mime.getSubject();
        } catch (MessagingException e) {
            subject = null;
        }
        return title(subject);
    }

    /** The subject as decoded, or null without one; for the key in it. */
    String subject() {
        try {
            return mime.getSubject();
        } catch (MessagingException e) {
            return null;
        }
    }

    /** {@link #title()} of a decoded subject; null is the empty text. */
    static String title(String subject) {
        if (subject == null) {
            return "";
        }
        String t = CONTROL.matcher(Normalizer.normalize(subject, Normalizer.Form.NFC)).replaceAll(" ").strip();
        return t.codePointCount(0, t.length()) <= TITLE_MAX ? t : t.substring(0, t.offsetByCodePoints(0, TITLE_MAX));
    }

    /**
     * A file name as kept (S12-AC-25): encoded words decoded, everything up to the last {@code /} or {@code \} taken
     * off, control and bidirectional control characters taken out, NFC. Empty when nothing is left.
     */
    static String fileName(String sent) {
        String name = sent;
        if (name.contains("=?")) {
            try {
                name = MimeUtility.decodeText(name);
            } catch (IOException e) {
                // Not encoded words after all; the name as sent.
                name = sent;
            }
        }
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = BIDI.matcher(CONTROL.matcher(name).replaceAll("")).replaceAll("");
        return Normalizer.normalize(name, Normalizer.Form.NFC).strip();
    }

    /** Whether the email has over {@value #MAX_PARTS} MIME parts or nests deeper than {@value #MAX_DEPTH}. */
    boolean tooComplex() {
        int[] parts = {0};
        return deeper(mime, 0, parts) || parts[0] > MAX_PARTS;
    }

    private static boolean deeper(Part part, int depth, int[] parts) {
        if (depth > MAX_DEPTH) {
            return true;
        }
        Multipart multipart = multipart(part);
        if (multipart == null) {
            return false;
        }
        try {
            for (int i = 0; i < multipart.getCount(); i++) {
                parts[0]++;
                if (parts[0] > MAX_PARTS || deeper(multipart.getBodyPart(i), depth + 1, parts)) {
                    return true;
                }
            }
        } catch (MessagingException e) {
            throw new IllegalStateException("The parts of a received email could not be read ("
                    + e.getClass().getSimpleName() + ")");
        }
        return false;
    }

    /**
     * The body as text (S12-AC-22): the first {@code text/plain} part that has no file name and is no attachment,
     * outside attached messages; else the first such {@code text/html} part as text ({@link HtmlText}). Its charset is
     * read; an unknown or broken one is read as UTF-8 with the replacement character. NFC, and without NUL, which
     * cannot be stored. Empty when there is none.
     */
    String body() {
        Part[] found = new Part[2];
        walk(mime, found, null);
        if (found[0] != null) {
            return clean(text(found[0]));
        }
        return found[1] == null ? "" : clean(HtmlText.text(text(found[1])));
    }

    /** The parts with a file name, attachments and inline parts alike, in their order, outside attached messages. */
    List<NamedPart> namedParts() {
        List<NamedPart> out = new ArrayList<>();
        walk(mime, new Part[2], out);
        return out;
    }

    private static void walk(Part part, Part[] texts, List<NamedPart> named) {
        try {
            Multipart multipart = multipart(part);
            if (multipart != null) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    walk(multipart.getBodyPart(i), texts, named);
                }
                return;
            }
            String name = part instanceof BodyPart ? name(part) : null;
            if (name != null) {
                if (named != null) {
                    named.add(new NamedPart(name, baseType(part), part));
                }
                return;
            }
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.isMimeType("message/rfc822")) {
                return;
            }
            if (texts[0] == null && part.isMimeType("text/plain")) {
                texts[0] = part;
            } else if (texts[1] == null && part.isMimeType("text/html")) {
                texts[1] = part;
            }
        } catch (MessagingException e) {
            throw new IllegalStateException("The parts of a received email could not be read ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** The part's file name as sent (RFC 2231 decoded by the library), or null without a non-empty one. */
    private static String name(Part part) {
        try {
            String name = part.getFileName();
            return name == null || name.isEmpty() ? null : name;
        } catch (MessagingException e) {
            return null;
        }
    }

    private static String baseType(Part part) {
        try {
            return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
        } catch (MessagingException e) {
            return "application/octet-stream";
        }
    }

    private static Multipart multipart(Part part) {
        try {
            if (!part.isMimeType("multipart/*")) {
                return null;
            }
            Object content = part.getContent();
            return content instanceof Multipart m ? m : null;
        } catch (MessagingException | IOException e) {
            return null;
        }
    }

    /** The text of a part in its charset; an unknown or broken charset is UTF-8 with replacement characters. */
    private static String text(Part part) {
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException("A part of a received email could not be read ("
                    + e.getClass().getSimpleName() + ")");
        }
        Charset charset = StandardCharsets.UTF_8;
        try {
            String name = new ContentType(part.getContentType()).getParameter("charset");
            if (name != null && Charset.isSupported(MimeUtility.javaCharset(name.strip()))) {
                charset = Charset.forName(MimeUtility.javaCharset(name.strip()));
            }
        } catch (MessagingException | IllegalArgumentException e) {
            // No charset that can be read: UTF-8 below.
            charset = StandardCharsets.UTF_8;
        }
        CharsetDecoder decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String clean(String text) {
        return Normalizer.normalize(text.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC);
    }

    /** The named part's contents, transfer encoding undone, as a temporary file named {@code name}. */
    static MailPartFile file(NamedPart part, String name) {
        try (InputStream in = part.part().getInputStream()) {
            return MailPartFile.of(name, part.contentType(), in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (MessagingException e) {
            throw new IllegalStateException("A part of a received email could not be read ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
