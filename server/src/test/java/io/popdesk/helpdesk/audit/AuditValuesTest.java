package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What an audit entry may hold and what the hash covers (design section 2): numbers are whole, and the
 * hash is computed from the stored text, strictly, so a value popdesk would not write has no hash.
 */
class AuditValuesTest {

    private static final Instant AT = Instant.parse("2026-10-08T03:15:00.123456789Z");

    private static final String ACTOR = "{\"name\": \"Kim\", \"email\": \"kim@example.test\", \"accountId\": 12}";

    static Stream<Object> numbersWithoutAStoredForm() {
        return Stream.of(1.5d, 1e21d, 0.1f, new BigDecimal("1.10"), BigInteger.TWO.pow(64), (byte) 1);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Values values(Map<String, ?> changes) {
        return AuditChain.values(AuditAction.COMPANY_UPDATED, 12, "Kim", "kim@example.test",
                Map.of("type", "COMPANY", "code", "ACME"), changes, AT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("numbersWithoutAStoredForm")
    @DisplayName("S1B-AC-09 a number that is not an Integer, Long, or Short is refused when the entry is recorded, "
            + "also inside a list or an object")
    void otherNumbersAreRefused(Object number) {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("from", null);
        nested.put("to", List.of("a", number));

        assertThatThrownBy(() -> values(Map.of("n", number))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values(Map.of("n", nested))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("S1B-AC-09 whole numbers, text, true and false, null, lists, and objects are stored as they are; "
            + "a time becomes the canonical text, and the entry's time is cut to microseconds")
    void allowedValuesAreStored() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("count", 5);
        changes.put("id", Long.MAX_VALUE);
        changes.put("small", (short) 3);
        changes.put("member", true);
        changes.put("none", null);
        changes.put("codes", List.of("A", "B"));
        changes.put("expiresAt", AT);

        Values values = values(changes);

        assertThat(values.action()).isEqualTo("COMPANY_UPDATED");
        assertThat(values.changes()).isEqualTo("{\"count\":5,\"id\":9223372036854775807,\"small\":3,"
                + "\"member\":true,\"none\":null,\"codes\":[\"A\",\"B\"],"
                + "\"expiresAt\":\"2026-10-08T03:15:00.123456Z\"}");
        assertThat(values.occurredAt()).isEqualTo(Instant.parse("2026-10-08T03:15:00.123456Z"));
        assertThat(AuditChain.values(AuditAction.SIGN_IN_SUCCEEDED, 0, null, null, null, null, AT).target()).isNull();
    }

    @Test
    @DisplayName("S1B-AC-09 the hash is SHA-256 of the previous hash's 64 characters and the canonical JSON, "
            + "whatever the order of keys and the spacing of the stored text")
    void hashIsOfThePreviousHashAndTheCanonicalJson() {
        Values stored = new Values("COMPANY_CREATED", ACTOR, "{\"type\":\"COMPANY\",\"code\":\"ACME\"}", null,
                Instant.parse("2026-10-08T03:15:00Z"));
        Values sameValues = new Values("COMPANY_CREATED",
                "{\"accountId\":12,\"email\":\"kim@example.test\",\"name\":\"Kim\"}",
                "{ \"code\": \"ACME\", \"type\": \"COMPANY\" }", "null", Instant.parse("2026-10-08T03:15:00Z"));

        String hash = AuditChain.hash(AuditChain.ZERO_HASH, stored);

        // printf '%s%s' "$(printf '0%.0s' $(seq 64))" '{"action":"COMPANY_CREATED","actor":{"accountId":12,
        // "email":"kim@example.test","name":"Kim"},"changes":null,"occurredAt":"2026-10-08T03:15:00.000000Z",
        // "target":{"code":"ACME","type":"COMPANY"}}' | sha256sum  (the JSON on one line, without spaces)
        assertThat(hash).isEqualTo(sha256(AuditChain.ZERO_HASH + "{\"action\":"
                + "\"COMPANY_CREATED\",\"actor\":{\"accountId\":12,\"email\":\"kim@example.test\",\"name\":\"Kim\"},"
                + "\"changes\":null,\"occurredAt\":\"2026-10-08T03:15:00.000000Z\","
                + "\"target\":{\"code\":\"ACME\",\"type\":\"COMPANY\"}}"));
        assertThat(AuditChain.hash(AuditChain.ZERO_HASH, sameValues)).isEqualTo(hash);
        assertThat(AuditChain.hash(hash, stored)).as("with another previous hash").isNotEqualTo(hash);
    }

    static Stream<Values> storedValuesPopdeskDoesNotWrite() {
        Instant at = Instant.parse("2026-10-08T03:15:00Z");
        String target = "{\"type\":\"COMPANY\"}";
        return Stream.of(
                new Values("X", "{\"accountId\":12,\"name\":\"Kim\"}", target, null, at),
                new Values("X", "{\"accountId\":12,\"name\":\"Kim\",\"email\":null,\"more\":1}", target, null, at),
                new Values("X", "{\"accountId\":12.0,\"name\":\"Kim\",\"email\":null}", target, null, at),
                new Values("X", "{\"accountId\":\"12\",\"name\":\"Kim\",\"email\":null}", target, null, at),
                new Values("X", "{\"accountId\":12,\"name\":7,\"email\":null}", target, null, at),
                new Values("X", "[]", target, null, at),
                new Values("X", ACTOR, "[\"COMPANY\"]", null, at),
                new Values("X", ACTOR, target, "{\"n\":1.0}", at),
                new Values("X", ACTOR, target, "{\"n\":[{\"m\":18446744073709551616}]}", at),
                new Values("X", ACTOR, target, "not json", at));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedValuesPopdeskDoesNotWrite")
    @DisplayName("S1B-AC-15 stored values popdesk does not write have no hash: a person that is not {accountId, "
            + "name, email}, a target that is not an object, a number with a fraction or over 64 bits")
    void valuesPopdeskDoesNotWriteHaveNoHash(Values stored) {
        assertThatThrownBy(() -> AuditChain.hash(AuditChain.ZERO_HASH, stored))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
