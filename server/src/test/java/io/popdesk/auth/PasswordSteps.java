package io.popdesk.auth;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steps of the password tests of slice 1a: asking for a reset, reading the reset mail, setting the
 * new password, and the forms a token or its hash could take in the database. Only HTTP and the
 * mail the app sent.
 *
 * <p>A reset link is {@code <address of the app>/reset-password#token=...} (design.md 5-1).
 */
final class PasswordSteps {

    static final String REQUEST = "/api/public/password-reset/request";
    static final String CONFIRM = "/api/public/password-reset/confirm";

    /** A password AUTH-5 accepts, other than {@link Fixtures#PASSWORD}. */
    static final String NEW_PASSWORD = "changed-Password-2";

    private static final String LINK_MARK = "/reset-password#token=";

    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>]+/reset-password#token=[^&\\s\"'<>]+");

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordSteps() {}

    /** New passwords AUTH-5 refuses, by the {@code fields[].code} of the 400. */
    enum Refused {
        TOO_SHORT,
        TOO_LONG,
        BLANK,
        SAME_AS_EMAIL;

        String password(String email) {
            return switch (this) {
                case TOO_SHORT -> "Nine-ch1x";
                case TOO_LONG -> "x".repeat(73);
                case BLANK -> " ".repeat(12);
                case SAME_AS_EMAIL -> email;
            };
        }
    }

    /** Asks for a reset mail on the client's host, whatever the answer. */
    static ApiResponse request(ApiClient client, String email) {
        return client.post(REQUEST).json(Map.of("email", email)).send();
    }

    /** Sets a new password with a token on the client's host, whatever the answer. */
    static ApiResponse confirm(ApiClient client, String token, String password) {
        return client.post(CONFIRM).json(Map.of("token", token, "password", password)).send();
    }

    /** The reset mails to an address so far, oldest first. */
    static List<ReceivedMail> mails(MailServer mail, String email) {
        return mail.messagesTo(email).stream().filter(m -> m.text().contains(LINK_MARK)).toList();
    }

    /** Waits until {@code n} reset mails went to the address, and returns them. */
    static List<ReceivedMail> awaitMails(MailServer mail, String email, int n) {
        return Wait.until(n + " reset mail(s) to " + email, () -> {
            List<ReceivedMail> found = mails(mail, email);
            return found.size() >= n ? found : null;
        });
    }

    /** Asks for a reset (202) for an address without an earlier reset mail, and returns the link from the mail. */
    static String requestLink(ApiClient client, MailServer mail, String email) {
        request(client, email).expectStatus(202);
        return linkIn(awaitMails(mail, email, 1).getFirst().text());
    }

    /** As {@link #requestLink}, the token of the link. */
    static String requestToken(ApiClient client, MailServer mail, String email) {
        return Fixtures.tokenOf(requestLink(client, mail, email));
    }

    /** The first reset link in a text (a mail body). */
    static String linkIn(String text) {
        Matcher m = LINK.matcher(text);
        if (!m.find()) {
            throw new AssertionError("No reset link in: " + text);
        }
        return m.group();
    }

    static boolean hasHangul(String text) {
        return text != null && HANGUL.matcher(text).find();
    }

    /** A token of the right shape that the app never made: 32 random bytes as base64url. */
    static String tokenNeverIssued() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The 32 bytes a token stands for, read as base64url or hex, if it is either. */
    static Optional<byte[]> tokenBytes(String token) {
        if (token.matches("[0-9a-fA-F]{64}")) {
            return Optional.of(HexFormat.of().parseHex(token));
        }
        if (token.matches("[A-Za-z0-9_-]{43}=?")) {
            return Optional.of(Base64.getUrlDecoder().decode(token));
        }
        return Optional.empty();
    }

    /** Ways a token itself could be stored: as given, and its raw bytes as hex. */
    static List<String> tokenForms(String token) {
        List<String> forms = new ArrayList<>(List.of(token));
        tokenBytes(token).ifPresent(b -> forms.add(HexFormat.of().formatHex(b)));
        return forms;
    }

    /**
     * Ways the SHA-256 of a token could be stored: of the token text or of its raw bytes, as hex
     * (either case; {@code bytea} reads as lower-case hex) or base64 (standard or URL-safe, with or
     * without padding).
     */
    static List<String> hashForms(String token) {
        List<byte[]> inputs = new ArrayList<>(List.of(token.getBytes(StandardCharsets.UTF_8)));
        tokenBytes(token).ifPresent(inputs::add);
        List<String> forms = new ArrayList<>();
        for (byte[] input : inputs) {
            byte[] hash = sha256(input);
            String hex = HexFormat.of().formatHex(hash);
            forms.add(hex);
            forms.add(hex.toUpperCase(Locale.ROOT));
            forms.add(Base64.getEncoder().encodeToString(hash));
            forms.add(Base64.getEncoder().withoutPadding().encodeToString(hash));
            forms.add(Base64.getUrlEncoder().encodeToString(hash));
            forms.add(Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        }
        return forms.stream().distinct().toList();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
