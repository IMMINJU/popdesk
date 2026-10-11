package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log of an app that runs in a container, through the admin API: a JSON Lines export
 * without a filter, checked as a hash chain while it is read, and the integrity check.
 *
 * <p>The chain rule is the one of {@code audit.AuditSteps} (that class is not visible from this
 * package): {@code hash = SHA-256(prevHash as 64 lowercase hex characters ‖ canonical JSON)}, where
 * the canonical JSON is the RFC 8785 text of {@code {action, actor{accountId, email, name}, changes,
 * occurredAt, target}} with null values kept and the time in UTC with six fraction digits. A file of
 * 100,000 lines is read line by line; only the lines a test asks for are kept.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
final class AuditFile {

    static final String LOG = "/api/console/audit-log";

    /** The {@code prevHash} of the first entry of a chain. */
    static final String ZERO_HASH = "0".repeat(64);

    static final String HASH_PATTERN = "^[0-9a-f]{64}$";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * An export that was a whole chain: the IDs of its lines in file order, the {@code seq} and
     * {@code prevHash} of its first line, and the lines the test asked to keep.
     */
    record Chain(List<Long> ids, long firstSeq, String firstPrevHash, List<JsonNode> kept) {}

    private AuditFile() {}

    /**
     * Exports the log as JSON Lines without a filter and checks every line while reading: its
     * {@code hash} is the hash computed again, its {@code prevHash} is the {@code hash} of the line
     * before, and {@code seq} goes up by one.
     */
    static Chain export(Person admin, Predicate<JsonNode> keep) {
        ApiResponse file = admin.client().get(LOG + "/export").query("format", "JSONL").send().expectStatus(200);
        List<Long> ids = new ArrayList<>();
        List<JsonNode> kept = new ArrayList<>();
        long firstSeq = -1;
        String firstPrevHash = null;
        long seqBefore = -1;
        String hashBefore = null;
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(file.body()),
                StandardCharsets.UTF_8))) {
            for (String text = lines.readLine(); text != null; text = lines.readLine()) {
                if (text.isBlank()) {
                    continue;
                }
                JsonNode line = parse(text);
                long seq = number(line, "seq");
                String prevHash = text(line, "prevHash");
                String hash = text(line, "hash");
                if (!hash.equals(hash(prevHash, line))) {
                    throw new AssertionError("The hash of seq " + seq + " is not SHA-256 of its prevHash and "
                            + chainJson(line) + ": " + text);
                }
                if (hashBefore == null) {
                    firstSeq = seq;
                    firstPrevHash = prevHash;
                } else if (seq != seqBefore + 1 || !prevHash.equals(hashBefore)) {
                    throw new AssertionError("The line after seq " + seqBefore + " (hash " + hashBefore
                            + ") does not follow it: " + text);
                }
                seqBefore = seq;
                hashBefore = hash;
                ids.add(number(line, "id"));
                if (keep.test(line)) {
                    kept.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(ids).as("lines of the export").isNotEmpty();
        return new Chain(ids, firstSeq, firstPrevHash, kept);
    }

    /** Runs the integrity check and expects "all entries match": 200, valid, a count, no reason, no entry. */
    static JsonNode assertValid(Person admin) {
        JsonNode answer = admin.client().post(LOG + "/verify").send().expectStatus(200).json();
        assertThat(answer.path("valid").isBoolean() && answer.path("valid").booleanValue())
                .as("valid in the integrity check's answer %s", answer).isTrue();
        assertThat(number(answer, "checkedCount")).as("checkedCount in %s", answer).isPositive();
        assertThat(answer.path("reason").isNull()).as("reason is null in %s", answer).isTrue();
        assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
        return answer;
    }

    static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("Not JSON: " + text, e);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new AssertionError("No text field '" + field + "' in " + node);
        }
        return value.stringValue();
    }

    static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No whole-number field '" + field + "' in " + node);
        }
        return value.asLong();
    }

    /** The RFC 8785 text of a JSON value that holds no fractions. */
    static String canonical(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** A time as the chain writes it: UTC, six fraction digits. */
    static String micros(String time) {
        try {
            return MICROS.format(OffsetDateTime.parse(time).toInstant().truncatedTo(ChronoUnit.MICROS));
        } catch (DateTimeParseException e) {
            throw new AssertionError("Not a time: '" + time + "'", e);
        }
    }

    /** The text the chain hashes for a line. */
    static String chainJson(JsonNode line) {
        JsonNode actor = present(line, "actor");
        StringBuilder out = new StringBuilder();
        out.append("{\"action\":");
        write(out, present(line, "action"));
        out.append(",\"actor\":{\"accountId\":");
        write(out, present(actor, "accountId"));
        out.append(",\"email\":");
        write(out, present(actor, "email"));
        out.append(",\"name\":");
        write(out, present(actor, "name"));
        out.append("},\"changes\":");
        write(out, present(line, "changes"));
        out.append(",\"occurredAt\":");
        string(out, micros(text(line, "occurredAt")));
        out.append(",\"target\":");
        write(out, present(line, "target"));
        out.append('}');
        return out.toString();
    }

    static String hash(String prevHash, JsonNode line) {
        try {
            byte[] input = (prevHash + chainJson(line)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode present(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new AssertionError("No field '" + field + "' in " + node);
        }
        return value;
    }

    private static void write(StringBuilder out, JsonNode value) {
        if (value.isNull()) {
            out.append("null");
        } else if (value.isString()) {
            string(out, value.stringValue());
        } else if (value.isBoolean()) {
            out.append(value.booleanValue());
        } else if (value.isIntegralNumber()) {
            out.append(value.bigIntegerValue());
        } else if (value.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode item : value) {
                out.append(first ? "" : ",");
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value.isObject()) {
            List<String> keys = new ArrayList<>(value.propertyNames());
            // String.compareTo compares UTF-16 code units, the order RFC 8785 asks for.
            keys.sort(String::compareTo);
            out.append('{');
            boolean first = true;
            for (String key : keys) {
                out.append(first ? "" : ",");
                first = false;
                string(out, key);
                out.append(':');
                write(out, value.get(key));
            }
            out.append('}');
        } else {
            throw new AssertionError("An audit value that is not text, a whole number, true, false, null, a list, "
                    + "or an object: " + value);
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
