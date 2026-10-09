package io.popdesk.helpdesk.audit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical JSON of an audit row (S0-AC-100, S0 premortem 9): the bytes the hash chain of slice 1
 * hashes, for old rows and new ones alike. Change nothing here without a migration plan: every byte
 * is part of the chain.
 *
 * <p>Rules: RFC 8785 (JCS). Keys sorted by UTF-16 code units, no whitespace, numbers in ECMAScript
 * form, strings escaped as {@code JSON.stringify} does. A field without a value is written as
 * {@code null}, never left out. Every {@link Instant} is UTC ISO-8601 with exactly six fraction
 * digits; finer parts are cut off (the column keeps microseconds). The person is always
 * {@code {accountId, email, name}}.
 */
public final class CanonicalAuditJson {

    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private CanonicalAuditJson() {}

    /**
     * The canonical bytes of one audit row. Map values may be String, Integer, Long, Double, Boolean,
     * null, Instant, Map with String keys, or List of those.
     */
    public static byte[] encode(String action, Long actorAccountId, String actorName, String actorEmail,
            Map<String, ?> target, Map<String, ?> changes, Instant occurredAt) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("accountId", actorAccountId);
        actor.put("name", actorName);
        actor.put("email", actorEmail);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("action", action);
        row.put("actor", actor);
        row.put("target", target);
        row.put("changes", changes);
        row.put("occurredAt", occurredAt);
        StringBuilder out = new StringBuilder(256);
        write(out, row);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** An instant as the audit log writes it: UTC, six fraction digits. */
    public static String time(Instant instant) {
        return MICROS.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b ? "true" : "false");
            case Integer i -> out.append(i.intValue());
            case Long l -> out.append(l.longValue());
            case Short s -> out.append(s.intValue());
            case Double d -> out.append(number(d));
            case Float f -> out.append(number(f.doubleValue()));
            case Instant instant -> string(out, time(instant));
            case Map<?, ?> map -> object(out, map);
            case Collection<?> list -> array(out, list);
            default -> throw new IllegalArgumentException("An audit value of type " + value.getClass().getName()
                    + " has no canonical form");
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String s)) {
                throw new IllegalArgumentException("An audit object key is not a string");
            }
            keys.add(s);
        }
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
            write(out, map.get(key));
        }
        out.append('}');
    }

    private static void array(StringBuilder out, Collection<?> list) {
        out.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(out, item);
        }
        out.append(']');
    }

    /** {@code JSON.stringify} escaping: the two-character escapes, other controls in six-character form, hex in lower case. */
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
                    boolean loneSurrogate = Character.isSurrogate(c) && !(Character.isHighSurrogate(c)
                            && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || loneSurrogate) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A double in ECMAScript {@code Number.prototype.toString} form (RFC 8785 section 3.2.2.3). */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("NaN and Infinity have no JSON form");
        }
        if (d == 0) {
            return "0";
        }
        // Double.toString gives the shortest digits that read back as d (JDK 19 and later).
        BigDecimal exact = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = exact.unscaledValue().toString();
        int k = digits.length();
        int n = k - exact.scale();
        StringBuilder out = new StringBuilder();
        if (d < 0) {
            out.append('-');
        }
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int e = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return out.toString();
    }
}
