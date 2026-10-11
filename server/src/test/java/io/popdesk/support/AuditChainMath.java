package io.popdesk.support;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hash chain of the audit log, computed without any class of the app, from what the API gives
 * (an entry of the log, or a line of a JSON Lines export), and what the steps of every package need
 * to read it: fields of an entry, and the integrity check's "all entries match".
 *
 * <p>{@link #hash} is {@code SHA-256(prevHash as 64 lowercase hex characters ‖ canonical JSON)}, and
 * {@link #chainJson} is the RFC 8785 text of {@code {action, actor{accountId, email, name}, changes,
 * occurredAt, target}} with null values kept and the time in UTC with six fraction digits. Audit
 * values hold no fractions, so a number that is not an integer fails here.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
public final class AuditChainMath {

    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** The {@code prevHash} of the first entry of a chain. */
    public static final String ZERO_HASH = "0".repeat(64);

    public static final String HASH_PATTERN = "^[0-9a-f]{64}$";

    private static final String VERIFY = "/api/console/audit-log/verify";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuditChainMath() {}

    /** JSON text as JSON. */
    public static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("Not JSON: " + text, e);
        }
    }

    /** A text field of an entry, or of an object inside it. Fails when it is missing or not text. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new AssertionError("No text field '" + field + "' in " + node);
        }
        return value.stringValue();
    }

    /** A whole-number field. Fails when it is missing or not a whole number. */
    public static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No whole-number field '" + field + "' in " + node);
        }
        return value.asLong();
    }

    /**
     * Runs the integrity check as this signed-in admin and expects "all entries match": 200, valid, a
     * count, no reason, no entry.
     */
    public static JsonNode assertValid(ApiClient admin) {
        JsonNode answer = admin.post(VERIFY).send().expectStatus(200).json();
        assertThat(answer.path("valid").isBoolean() && answer.path("valid").booleanValue())
                .as("valid in the integrity check's answer %s", answer).isTrue();
        assertThat(number(answer, "checkedCount")).as("checkedCount in %s", answer).isPositive();
        assertThat(answer.path("reason").isNull()).as("reason is null in %s", answer).isTrue();
        assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
        return answer;
    }

    /**
     * The RFC 8785 text of a JSON value: keys sorted by UTF-16 code units, no whitespace, strings
     * escaped as {@code JSON.stringify} does. Two values are the same JSON when this text is the same,
     * whatever the order of their keys. A number with a fraction fails.
     */
    public static String canonical(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** The text the chain hashes for an entry (a log entry or an export line). */
    public static String chainJson(JsonNode entry) {
        JsonNode actor = present(entry, "actor");
        StringBuilder out = new StringBuilder();
        out.append("{\"action\":");
        write(out, present(entry, "action"));
        out.append(",\"actor\":{\"accountId\":");
        write(out, present(actor, "accountId"));
        out.append(",\"email\":");
        write(out, present(actor, "email"));
        out.append(",\"name\":");
        write(out, present(actor, "name"));
        out.append("},\"changes\":");
        write(out, present(entry, "changes"));
        out.append(",\"occurredAt\":");
        string(out, micros(present(entry, "occurredAt")));
        out.append(",\"target\":");
        write(out, present(entry, "target"));
        out.append('}');
        return out.toString();
    }

    /** {@code SHA-256(prevHash ‖ chainJson(entry))} as 64 lowercase hex characters. */
    public static String hash(String prevHash, JsonNode entry) {
        try {
            byte[] input = (prevHash + chainJson(entry)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String micros(JsonNode time) {
        if (!time.isString()) {
            throw new AssertionError("occurredAt is not text: " + time);
        }
        try {
            return MICROS.format(OffsetDateTime.parse(time.stringValue()).toInstant().truncatedTo(ChronoUnit.MICROS));
        } catch (DateTimeParseException e) {
            throw new AssertionError("Not a time: '" + time.stringValue() + "'", e);
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
        if (value == null || value.isMissingNode()) {
            throw new AssertionError("A missing value has no canonical form");
        } else if (value.isNull()) {
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
                if (!first) {
                    out.append(',');
                }
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
                if (!first) {
                    out.append(',');
                }
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
