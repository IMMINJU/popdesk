package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical JSON of an audit row (S0 premortem 9): the one function that turns an audit row
 * into the bytes the hash chain of slice 1 will hash, for old rows and new ones alike.
 *
 * <p>The function this test calls, which the implementation provides:
 *
 * <pre>
 * package io.popdesk.helpdesk.audit;
 * public final class CanonicalAuditJson {
 *     public static byte[] encode(String action, Long actorAccountId, String actorName, String actorEmail,
 *             Map&lt;String, ?&gt; target, Map&lt;String, ?&gt; changes, Instant occurredAt)
 * }
 * </pre>
 *
 * The result is the UTF-8 bytes of one JSON object with the keys {@code action}, {@code actor}
 * ({@code {accountId, name, email}}), {@code changes}, {@code occurredAt}, {@code target}. Rules:
 * RFC 8785 (keys sorted, no whitespace, ECMAScript number form, strings escaped as JSON.stringify
 * does), values without a value written as {@code null} and never left out, every
 * {@link Instant} (the time of the row and any inside {@code changes}) as UTC ISO-8601 with exactly
 * six fraction digits. Map values may be String, Integer, Long, Double, Boolean, null, Instant,
 * Map, or List.
 *
 * <p>This is the only test that calls production code directly. It finds the class by reflection,
 * so the test compiles before the class exists and fails with an AssertionError until it does. The
 * expected bytes were written by hand, and their sha256 computed from those bytes.
 */
class CanonicalAuditJsonTest {

    static final String CLASS = "io.popdesk.helpdesk.audit.CanonicalAuditJson";

    static final String METHOD = "encode";

    private static Method encoder() {
        Class<?> type;
        try {
            type = Class.forName(CLASS);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("No class " + CLASS + " (S0-AC-100)", e);
        }
        Method method;
        try {
            method = type.getMethod(METHOD, String.class, Long.class, String.class, String.class, Map.class,
                    Map.class, Instant.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("No method " + CLASS + "." + METHOD
                    + "(String, Long, String, String, Map, Map, Instant)", e);
        }
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != byte[].class) {
            throw new AssertionError(CLASS + "." + METHOD + " must be static and return byte[]: " + method);
        }
        return method;
    }

    private static byte[] encode(String action, Long actorAccountId, String actorName, String actorEmail,
            Map<String, ?> target, Map<String, ?> changes, Instant occurredAt) {
        try {
            Object out = encoder().invoke(null, action, actorAccountId, actorName, actorEmail, target, changes,
                    occurredAt);
            if (out == null) {
                throw new AssertionError(CLASS + "." + METHOD + " returned null");
            }
            return (byte[]) out;
        } catch (IllegalAccessException e) {
            throw new AssertionError(CLASS + "." + METHOD + " is not public", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(CLASS + "." + METHOD + " threw " + e.getCause(), e.getCause());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertCanonical(byte[] actual, String expected, String expectedSha256) {
        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(expected);
        assertThat(actual).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(sha256(actual)).isEqualTo(expectedSha256);
    }

    /** A map that keeps the order the test gives, so the function has to sort. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("S0-AC-100 a company row: keys sorted, no whitespace, person as {accountId, name, email}, microseconds")
    void companyRow() {
        byte[] bytes = encode("COMPANY_CREATED", 7L, "김관리", "admin@example.test",
                map("type", "COMPANY", "code", "ACME"),
                map("status", "ACTIVE", "name", "Acme", "legalName", "Acme Trading Ltd."),
                Instant.parse("2026-10-08T03:15:00.123456Z"));

        assertCanonical(bytes,
                "{\"action\":\"COMPANY_CREATED\",\"actor\":{\"accountId\":7,\"email\":\"admin@example.test\","
                        + "\"name\":\"김관리\"},\"changes\":{\"legalName\":\"Acme Trading Ltd.\",\"name\":\"Acme\","
                        + "\"status\":\"ACTIVE\"},\"occurredAt\":\"2026-10-08T03:15:00.123456Z\","
                        + "\"target\":{\"code\":\"ACME\",\"type\":\"COMPANY\"}}",
                "a6c8943f375f3ba49200fe8a480853e812803f05fe67699bd6724a78e9ab4c51");
    }

    @Test
    @DisplayName("S0-AC-100 the system person (account 0) keeps null name and email, empty lists and objects stay, "
            + "whole seconds get six zeros")
    void systemRowWithEmptyValues() {
        byte[] bytes = encode("FIRST_ADMIN_CREATED", 0L, null, null,
                map("type", "ACCOUNT", "accountId", 1L),
                map("role", "ADMIN", "settings", Map.of(), "note", null, "companyCodes", List.of()),
                Instant.parse("2026-10-08T03:15:00Z"));

        assertCanonical(bytes,
                "{\"action\":\"FIRST_ADMIN_CREATED\",\"actor\":{\"accountId\":0,\"email\":null,\"name\":null},"
                        + "\"changes\":{\"companyCodes\":[],\"note\":null,\"role\":\"ADMIN\",\"settings\":{}},"
                        + "\"occurredAt\":\"2026-10-08T03:15:00.000000Z\","
                        + "\"target\":{\"accountId\":1,\"type\":\"ACCOUNT\"}}",
                "16b113d8c69c3c652c3483e1354faac3f53f8b5bc2b7d0bdfb3967db105dbbb1");
    }

    @Test
    @DisplayName("S0-AC-100 fields without a value are written as null, not left out")
    void nullsAreKept() {
        byte[] bytes = encode("SIGN_IN_FAILED", null, null, null, null, null,
                Instant.parse("2026-10-08T03:15:07.000001Z"));

        assertCanonical(bytes,
                "{\"action\":\"SIGN_IN_FAILED\",\"actor\":{\"accountId\":null,\"email\":null,\"name\":null},"
                        + "\"changes\":null,\"occurredAt\":\"2026-10-08T03:15:07.000001Z\",\"target\":null}",
                "d69ba9e11f7fe1cc3389d2484084ace1e88d77f191b1d0ef6b8f0d42ffae2bc9");
    }

    @Test
    @DisplayName("S0-AC-100 nested keys sorted by code point, ECMAScript numbers, JSON.stringify string escapes, "
            + "times inside changes")
    void sortingNumbersAndEscapes() {
        String text = "quote\" backslash\\ slash/ tab\t newline\n cr\r bs\b ff\f unit" + (char) 0x1f
                + " del" + (char) 0x7f + " ls" + (char) 0x2028 + " 한글 " + Character.toString(0x1F600);
        Map<String, Object> changes = map(
                "text", text,
                "numbers", Arrays.asList(0, -7, 42L, 1.5, -0.0, 100.0, 1e21, 1e20, 1e-7, 0.000001, 123456789012.0,
                        true, false, null),
                "nested", map("z", map("y", 1, "x", List.of(map("b", 2, "a", 1)))),
                "가", 4,
                "aa", 3,
                "a", 2,
                "B", 1,
                "expiresAt", Instant.parse("2026-10-15T03:15:00.5Z"));

        byte[] bytes = encode("INVITATION_CREATED", 12L, "이민", "lee@example.test",
                map("type", "ACCOUNT", "accountId", 34L), changes, Instant.parse("2026-12-31T23:59:59.999999Z"));

        String escaped = "\"quote\\\" backslash\\\\ slash/ tab\\t newline\\n cr\\r bs\\b ff\\f unit\\u001f del"
                + (char) 0x7f + " ls" + (char) 0x2028 + " 한글 " + Character.toString(0x1F600) + "\"";
        assertCanonical(bytes,
                "{\"action\":\"INVITATION_CREATED\",\"actor\":{\"accountId\":12,\"email\":\"lee@example.test\","
                        + "\"name\":\"이민\"},\"changes\":{\"B\":1,\"a\":2,\"aa\":3,"
                        + "\"expiresAt\":\"2026-10-15T03:15:00.500000Z\","
                        + "\"nested\":{\"z\":{\"x\":[{\"a\":1,\"b\":2}],\"y\":1}},"
                        + "\"numbers\":[0,-7,42,1.5,0,100,1e+21,100000000000000000000,1e-7,0.000001,123456789012,"
                        + "true,false,null],\"text\":" + escaped + ",\"가\":4},"
                        + "\"occurredAt\":\"2026-12-31T23:59:59.999999Z\","
                        + "\"target\":{\"accountId\":34,\"type\":\"ACCOUNT\"}}",
                "2650007be29b48c24315c6b3830b7b69d8caa4eeb1d0b0786e8e294a796a61ef");
    }

    @Test
    @DisplayName("S0-AC-100 the same row gives the same bytes whatever order its maps were built in")
    void independentOfMapOrder() {
        Instant at = Instant.parse("2026-10-08T03:15:00.123456Z");

        byte[] one = encode("AGENT_ASSIGNED", 3L, "Kim", "kim@example.test",
                map("type", "COMPANY", "code", "ACME", "accountId", 9L), map("assigned", true), at);
        byte[] two = encode("AGENT_ASSIGNED", 3L, "Kim", "kim@example.test",
                map("accountId", 9L, "code", "ACME", "type", "COMPANY"), map("assigned", true), at);

        assertThat(two).isEqualTo(one);
        assertThat(new String(one, StandardCharsets.UTF_8)).contains(
                "\"target\":{\"accountId\":9,\"code\":\"ACME\",\"type\":\"COMPANY\"}");
    }
}
