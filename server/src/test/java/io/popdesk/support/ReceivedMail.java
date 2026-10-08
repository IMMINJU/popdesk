package io.popdesk.support;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One mail GreenMail received, already read: recipients, subject (decoded), the text of every
 * text part (plain and HTML, decoded), all headers, and the raw MIME source for searches that must
 * also see encoded forms.
 */
public record ReceivedMail(
        String from,
        List<String> to,
        List<String> cc,
        String subject,
        String text,
        Map<String, List<String>> headers,
        String raw) {

    static ReceivedMail of(MimeMessage message) throws MessagingException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header h : Collections.list(message.getAllHeaders())) {
            headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
        }
        StringBuilder text = new StringBuilder();
        try {
            collectText(message, text);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            message.writeTo(raw);
            return new ReceivedMail(
                    addresses(message.getFrom()).stream().findFirst().orElse(null),
                    addresses(message.getRecipients(Message.RecipientType.TO)),
                    addresses(message.getRecipients(Message.RecipientType.CC)),
                    message.getSubject(),
                    text.toString(),
                    headers,
                    raw.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MessagingException("Could not read the mail body", e);
        }
    }

    /** Whether {@code address} is a To recipient, ignoring case. */
    public boolean isTo(String address) {
        return to.stream().anyMatch(a -> a.equalsIgnoreCase(address));
    }

    /** All values of a header, names ignoring case. */
    public List<String> header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .toList();
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

    private static List<String> addresses(Address[] addresses) {
        if (addresses == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Address a : addresses) {
            out.add(a instanceof InternetAddress ia ? ia.getAddress() : a.toString());
        }
        return out;
    }
}
