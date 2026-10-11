package io.popdesk.helpdesk.audit;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hash chain of the audit log (AUDIT-6, S1B-AC-09): the values of an entry, and the one function
 * from a stored row to its hash. Appending a new entry, chaining the rows of older versions (V7), and
 * the integrity check all compute a hash here. Everything is static and needs no database, so the
 * app, the commands in the container, and the migration run the same code.
 *
 * <p>{@code hash = SHA-256(prevHash as 64 lowercase hex characters ‖ canonical JSON)}; the first
 * entry's {@code prevHash} is 64 zeros (docs/integrate/conventions.md "Audit log hash chain"). The
 * canonical JSON is computed from the values as the database gives them back, never from the values
 * the app meant to store: {@code jsonb} may write a value another way than the app did
 * ({@link AuditChainRepository#append} sends them there and back first).
 */
public final class AuditChain {

    /** The {@code prevHash} of the first entry, and the anchor of a chain nothing was removed from. */
    public static final String ZERO_HASH = "0".repeat(64);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> ACTOR_KEYS = Set.of("accountId", "name", "email");

    private AuditChain() {}

    /**
     * The values of an audit entry that the hash covers. {@code actor}, {@code target} and
     * {@code changes} are JSON text; the last two may be null. The time has microseconds.
     */
    public record Values(String action, String actor, String target, String changes, Instant occurredAt) {}

    /**
     * The values of a new entry, as JSON text ready to store. Instants become the canonical time text.
     * A number must be an {@code Integer}, {@code Long} or {@code Short}: {@code jsonb} rewrites
     * fractions and large numbers, so a hash over them could not be computed again from an export.
     *
     * @throws IllegalArgumentException for any other number
     */
    public static Values values(AuditAction action, long actorAccountId, String actorName, String actorEmail,
            Map<String, ?> target, Map<String, ?> changes, Instant occurredAt) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("accountId", actorAccountId);
        actor.put("name", actorName);
        actor.put("email", actorEmail);
        return new Values(action.name(), JSON.writeValueAsString(actor), toJson(target), toJson(changes),
                occurredAt.truncatedTo(ChronoUnit.MICROS));
    }

    private static String toJson(Map<String, ?> value) {
        return value == null ? null : JSON.writeValueAsString(storable(value));
    }

    private static Object storable(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> CanonicalAuditJson.time(instant);
            case Map<?, ?> map -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(k, storable(v)));
                yield out;
            }
            case Collection<?> list -> {
                List<Object> out = new ArrayList<>();
                list.forEach(v -> out.add(storable(v)));
                yield out;
            }
            case Integer i -> i;
            case Long l -> l;
            case Short s -> s;
            case Number n -> throw new IllegalArgumentException("An audit value of type " + n.getClass().getName()
                    + " is not a whole number of at most 64 bits");
            default -> value;
        };
    }

    /**
     * The hash of a stored row: SHA-256 of the previous hash's 64 characters and the row's canonical
     * JSON, as 64 lowercase hex characters. {@code stored} holds the values as read from the database.
     *
     * @throws IllegalArgumentException when the values have no canonical form: the person is not
     *     {@code {accountId, name, email}}, a target that is not an object, a number that is not whole
     */
    public static String hash(String prevHash, Values stored) {
        Map<String, Object> actor = object(stored.actor());
        if (actor == null || !actor.keySet().equals(ACTOR_KEYS)) {
            throw new IllegalArgumentException("The person of an audit row is not {accountId, name, email}");
        }
        byte[] canonical = CanonicalAuditJson.encode(stored.action(), whole(actor.get("accountId")),
                text(actor.get("name")), text(actor.get("email")), object(stored.target()),
                object(stored.changes()), stored.occurredAt());
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prevHash.getBytes(StandardCharsets.UTF_8));
            sha.update(canonical);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> object(String json) {
        if (json == null) {
            return null;
        }
        Object value;
        try {
            value = JSON.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("An audit value is not JSON");
        }
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("An audit value is not a JSON object");
        }
        requireWholeNumbers(map);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) map;
        return out;
    }

    /** A fraction in a stored row can only come from a change in the database: new entries hold none. */
    private static void requireWholeNumbers(Object value) {
        switch (value) {
            case Map<?, ?> map -> map.values().forEach(AuditChain::requireWholeNumbers);
            case Collection<?> list -> list.forEach(AuditChain::requireWholeNumbers);
            case Double d -> throw new IllegalArgumentException("An audit value is a number with a fraction");
            case BigInteger b -> throw new IllegalArgumentException("An audit value is a number over 64 bits");
            case null, default -> { }
        }
    }

    /** The database gives a small number back as an {@code Integer}. */
    private static Long whole(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("The account ID of an audit row's person is not a whole number");
    }

    private static String text(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("The name or email of an audit row's person is not text");
    }
}
