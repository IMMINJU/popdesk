package io.popdesk.helpdesk.issue;

import io.popdesk.ApiError;
import io.popdesk.helpdesk.issue.IssueService.Draft;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * The {@code Idempotency-Key} a filing was sent with, and the hash of what the filing holds (ISSUE-12,
 * conventions "Filing an issue once"). The key belongs to the account that sent it. A filing sent
 * again with the key and the same hash gets the issue the first one made; with another hash it is
 * refused. Neither the key nor the hash is written to a log.
 *
 * @param value the header as sent
 * @param requestHash SHA-256 of the filing's fields, in hexadecimal
 */
public record IdempotencyKey(String value, String requestHash) {

    static final String HEADER = "Idempotency-Key";
    static final int MAX = 100;

    /** How long a key answers with its issue; after that it files a new one (S3-AC-46). */
    static final Duration KEPT = Duration.ofHours(24);

    /**
     * Whether the header is 1 to {@link #MAX} visible ASCII characters, {@code !} to {@code ~}
     * (S3-AC-45). One that is not gets an entry in {@code errors}, under the header's name: an empty
     * header is a wrong key, not a missing one.
     */
    static boolean valid(String received, List<ApiError.FieldError> errors) {
        if (received.isEmpty() || !received.chars().allMatch(c -> c >= '!' && c <= '~')) {
            errors.add(new ApiError.FieldError(HEADER, "INVALID_FORMAT"));
            return false;
        }
        if (received.length() > MAX) {
            errors.add(new ApiError.FieldError(HEADER, "TOO_LONG"));
            return false;
        }
        return true;
    }

    /**
     * The key of a checked filing. The hash takes the fields that make it another filing (S3-AC-42), as
     * they would be stored: the company code, the trimmed title, the content, the priority with its
     * default, whether it is private, the reporter, and the channel. The last two differ only between
     * filings by staff; files are left out. Each value goes in with its length before it, so two
     * different filings never give the same bytes.
     */
    static IdempotencyKey of(String value, Draft draft) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String part : List.of(draft.company().code(), draft.title(), draft.content(), draft.priority().name(),
                Boolean.toString(draft.privateIssue()), Long.toString(draft.reporterId()), draft.channel())) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            sha.update(bytes);
        }
        return new IdempotencyKey(value, HexFormat.of().formatHex(sha.digest()));
    }

    /** Without the key and the hash, so that a draft printed by mistake does not show them. */
    @Override
    public String toString() {
        return "IdempotencyKey[hidden]";
    }
}
