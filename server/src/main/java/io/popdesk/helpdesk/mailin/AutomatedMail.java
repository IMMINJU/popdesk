package io.popdesk.helpdesk.mailin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a received email is discarded before it is read as a customer's (MAILIN-7, TM-22, TM-23): mail a machine
 * sent (S12-AC-11), and mail whose {@code Authentication-Results} say DMARC failed (S12-AC-12, D-68). Pure: the
 * headers in, a reason or none out.
 */
final class AutomatedMail {

    /** Why an email is discarded here; stored as the record's {@code reason}. */
    enum Reason {
        AUTOMATED,
        DMARC_FAIL
    }

    /** What the {@code Authentication-Results} headers say of DMARC. */
    enum Dmarc {
        /** One of them says {@code dmarc=fail}. */
        FAIL,
        /** None says fail, one says {@code dmarc=pass}. */
        PASS,
        /** No DMARC result, or only {@code none}, {@code temperror}, {@code permerror}, or another word. */
        NONE
    }

    private static final Set<String> BULK = Set.of("bulk", "list", "junk", "auto_reply");

    /** A method and its result at the start of one result of {@code Authentication-Results}: {@code dmarc = fail}. */
    private static final Pattern RESULT = Pattern.compile("\\s*([A-Za-z0-9_.-]+)(?:/[0-9]+)?\\s*=\\s*([A-Za-z0-9_-]+)");

    private AutomatedMail() {}

    /**
     * The reason to discard the email, in the order of S12-AC-11 then S12-AC-12, or empty. Automated when any of:
     * an {@code Auto-Submitted} whose value (before any {@code ;} and comment) is not {@code no}; a
     * {@code Precedence} of {@code bulk}, {@code list}, {@code junk}, or {@code auto_reply}; an {@code X-Autoreply}
     * or {@code X-Autorespond} header; a top {@code Content-Type} of {@code multipart/report}; a
     * {@code Return-Path} of {@code <>}; a {@code From} address that is {@code sendingAddress} or
     * {@code inboundAddress}, also in its {@code +code} form. Every comparison ignores letter case.
     *
     * @param headers gives the raw values of a header by its name, in any letter case; an empty list when the email
     *     has none; never null
     * @param topContentType the {@code Content-Type} of the email itself, or null without one
     * @param fromAddress the one address of {@code From} (addr-spec), or null when {@code From} is not one address:
     *     then only the headers are looked at
     * @param sendingAddress the address popdesk sends from; may be null
     * @param inboundAddress {@code MAIL_INBOUND_ADDRESS}; may be null
     */
    static Optional<Reason> reason(Function<String, List<String>> headers, String topContentType, String fromAddress,
            String sendingAddress, String inboundAddress) {
        if (automated(headers, topContentType, fromAddress, sendingAddress, inboundAddress)) {
            return Optional.of(Reason.AUTOMATED);
        }
        if (dmarc(headers.apply("Authentication-Results")) == Dmarc.FAIL) {
            return Optional.of(Reason.DMARC_FAIL);
        }
        return Optional.empty();
    }

    private static boolean automated(Function<String, List<String>> headers, String topContentType,
            String fromAddress, String sendingAddress, String inboundAddress) {
        for (String value : headers.apply("Auto-Submitted")) {
            if (!firstWord(value).equals("no")) {
                return true;
            }
        }
        for (String value : headers.apply("Precedence")) {
            if (BULK.contains(firstWord(value))) {
                return true;
            }
        }
        if (!headers.apply("X-Autoreply").isEmpty() || !headers.apply("X-Autorespond").isEmpty()) {
            return true;
        }
        if (topContentType != null && firstWord(topContentType).equals("multipart/report")) {
            return true;
        }
        for (String value : headers.apply("Return-Path")) {
            if (withoutComments(unfold(value)).replaceAll("\\s", "").equals("<>")) {
                return true;
            }
        }
        return fromAddress != null && (sameAddress(fromAddress, sendingAddress)
                || InboundAddress.isInbound(fromAddress, inboundAddress));
    }

    /**
     * What the {@code Authentication-Results} headers say of DMARC (S12-AC-12, RFC 8601): each header unfolded and
     * its comments taken out, split at {@code ;}, the first part (the server's name) skipped, and each other part
     * read as {@code method=result} with spaces allowed around {@code =} and a method version ({@code dmarc/1})
     * allowed. Method and result ignore letter case. One {@code fail} anywhere is {@link Dmarc#FAIL}.
     *
     * @param values the raw values of every {@code Authentication-Results} header, possibly folded; may be empty
     */
    static Dmarc dmarc(List<String> values) {
        boolean pass = false;
        for (String value : values) {
            String[] parts = withoutComments(unfold(value)).split(";");
            for (int i = 1; i < parts.length; i++) {
                Matcher m = RESULT.matcher(parts[i]);
                if (!m.lookingAt() || !m.group(1).equalsIgnoreCase("dmarc")) {
                    continue;
                }
                String result = m.group(2).toLowerCase(Locale.ROOT);
                if (result.equals("fail")) {
                    return Dmarc.FAIL;
                }
                pass |= result.equals("pass");
            }
        }
        return pass ? Dmarc.PASS : Dmarc.NONE;
    }

    /** The value up to its first {@code ;}, unfolded, without comments, trimmed, in lower case. */
    private static String firstWord(String value) {
        String v = withoutComments(unfold(value));
        int semicolon = v.indexOf(';');
        return (semicolon < 0 ? v : v.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean sameAddress(String a, String b) {
        return b != null && InboundAddress.normal(a).equals(InboundAddress.normal(b.strip()));
    }

    /** A header value on one line: the line breaks of folding taken out, the space after them kept (RFC 5322). */
    static String unfold(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    /**
     * The value without its comments: text in parentheses, which may nest and may hold {@code \}-quoted characters,
     * outside quoted strings. An unclosed comment runs to the end.
     */
    static String withoutComments(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && (quoted || depth > 0)) {
                if (depth == 0) {
                    out.append(c).append(value.charAt(i + 1));
                }
                i++;
            } else if (depth == 0 && c == '"') {
                quoted = !quoted;
                out.append(c);
            } else if (!quoted && c == '(') {
                depth++;
                if (depth == 1) {
                    out.append(' ');
                }
            } else if (!quoted && c == ')' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
