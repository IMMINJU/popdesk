package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * More fixed answers for the canonical JSON of an audit row (S0 premortem 9), for values
 * {@link CanonicalAuditJsonTest} does not have: numbers between 0 and 1 that are written as plain
 * decimals ({@code 0.5}, not {@code .5} or {@code 5e-1}), and a surrogate without its pair at the end
 * or the start of a string, which JSON.stringify writes as the escape of that code unit.
 *
 * <p>Like {@link CanonicalAuditJsonTest}, this calls the one function by reflection:
 * {@code io.popdesk.helpdesk.audit.CanonicalAuditJson.encode(String, Long, String, String, Map, Map,
 * Instant)}. The expected bytes were written by hand, and their sha256 computed from those bytes.
 */
class CanonicalAuditJsonEdgesTest {

    private static byte[] encode(String action, Long actorAccountId, String actorName, String actorEmail,
            Map<String, ?> target, Map<String, ?> changes, Instant occurredAt) {
        try {
            Method method = Class.forName(CanonicalAuditJsonTest.CLASS).getMethod(CanonicalAuditJsonTest.METHOD,
                    String.class, Long.class, String.class, String.class, Map.class, Map.class, Instant.class);
            return (byte[]) method.invoke(null, action, actorAccountId, actorName, actorEmail, target, changes,
                    occurredAt);
        } catch (InvocationTargetException e) {
            throw new AssertionError(CanonicalAuditJsonTest.CLASS + "." + CanonicalAuditJsonTest.METHOD + " threw "
                    + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("No public static " + CanonicalAuditJsonTest.CLASS + "."
                    + CanonicalAuditJsonTest.METHOD + " (S0-AC-100)", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("S0-AC-100 numbers between 0 and 1 are plain decimals, and a surrogate without its pair at the end or start of a string is escaped")
    void fractionsAndLoneSurrogates() {
        char high = (char) 0xD83D;
        char low = (char) 0xDE00;
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("tail", "high at the end " + high);
        changes.put("numbers", Arrays.asList(0.5, 0.25, -0.5, 0.1, 0.999, 123.456));
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("head", low + " low at the start");

        byte[] bytes = encode("COMPANY_CREATED", 7L, "Kim", "kim@example.test", target, changes,
                Instant.parse("2026-10-08T03:15:00.123456Z"));

        String expected = "{\"action\":\"COMPANY_CREATED\",\"actor\":{\"accountId\":7,\"email\":\"kim@example.test\","
                + "\"name\":\"Kim\"},\"changes\":{\"numbers\":[0.5,0.25,-0.5,0.1,0.999,123.456],"
                + "\"tail\":\"high at the end \\ud83d\"},\"occurredAt\":\"2026-10-08T03:15:00.123456Z\","
                + "\"target\":{\"head\":\"\\ude00 low at the start\"}}";
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(expected);
        assertThat(bytes).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
        assertThat(sha256(bytes)).isEqualTo("6ad54cb5199410317d4f0cbc6283eb3b333622b3f4e8e0320e757ee3a8fbbaca");
    }
}
