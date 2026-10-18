package io.popdesk.helpdesk.audit;

import io.popdesk.helpdesk.audit.AuditChain.Values;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts the anonymous values of an anonymized account into the person fields of one audit entry
 * (PII-5, S10A-AC-17, design.md 0절 4). A pure function of its arguments: the recalculation of the
 * chain ({@link AuditRechain}) and appending ({@link AuditChainRepository#append}) both call it, and
 * neither needs the database for it.
 *
 * <p>The person is found by account ID only, never by a name or an email, so another account with the
 * same name keeps its values.
 */
public final class AuditAnonymizer {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private AuditAnonymizer() {}

    /**
     * The entry with the anonymous {@code name} and {@code email} of account {@code accountId} in its
     * person fields, and every other value as it was.
     *
     * <p>What it takes and what it changes:
     *
     * <ul>
     *   <li>{@code actor} is the account (its {@code accountId} is {@code accountId}): its {@code name}
     *       and {@code email} become the anonymous ones. An actor without those keys gets them.
     *   <li>{@code target} is an object whose {@code accountId} is {@code accountId}, and {@code changes}
     *       is an object: its top-level {@code name} and {@code email} become the anonymous ones. A text
     *       value, or any other value but JSON null, is replaced by the text. An object (the
     *       {@code {from, to}} of a change) gets its {@code from} and {@code to} replaced, each one it
     *       has, whatever it holds; other keys of that object stay. A key that is missing or JSON null
     *       stays as it is.
     *   <li>Both can hold for one entry; each is done.
     *   <li>Neither holds (another account's entry, a target without an account, a {@code changes} that
     *       is null or not an object): nothing changes.
     *   <li>Every other key, of {@code actor}, {@code target}, {@code changes}, and the nested objects,
     *       stays with its value, in its order. {@code action} and {@code occurredAt} stay.
     *   <li>A value that is not JSON, or JSON that is not an object, is left as it is: such an entry
     *       was not written by popdesk, and the hash says so ({@link AuditChain#hash}).
     * </ul>
     *
     * <p>The JSON of a changed {@code actor} or {@code changes} is new text; send it through the
     * database before its hash is computed, as for any entry ({@link AuditChain}).
     *
     * @param row the entry's values as stored
     * @param accountId the anonymized account; never 0, popdesk itself
     * @param name the anonymous name
     * @param email the anonymous email, {@code deleted-{id}@invalid}
     * @return {@code row} itself when no value changes (also when the values are anonymous already),
     *     otherwise new values
     */
    public static Values anonymize(Values row, long accountId, String name, String email) {
        String actor = row.actor();
        Map<String, Object> actorValues = object(actor);
        if (actorValues != null && isAccount(actorValues.get("accountId"), accountId)) {
            boolean changed = put(actorValues, "name", name) | put(actorValues, "email", email);
            if (changed) {
                actor = JSON.writeValueAsString(actorValues);
            }
        }
        String changes = row.changes();
        Map<String, Object> target = object(row.target());
        Map<String, Object> changeValues = object(changes);
        if (target != null && isAccount(target.get("accountId"), accountId) && changeValues != null) {
            boolean changed = replace(changeValues, "name", name) | replace(changeValues, "email", email);
            if (changed) {
                changes = JSON.writeValueAsString(changeValues);
            }
        }
        if (Objects.equals(actor, row.actor()) && Objects.equals(changes, row.changes())) {
            return row;
        }
        return new Values(row.action(), actor, row.target(), changes, row.occurredAt());
    }

    /**
     * The accounts an entry can name a person of: the actor's {@code accountId} and the target's, when
     * they are whole numbers. Every account {@link #anonymize} can change the entry for is among them.
     */
    static Set<Long> accountIds(Values row) {
        Set<Long> out = new HashSet<>();
        for (String json : new String[] {row.actor(), row.target()}) {
            Map<String, Object> values = object(json);
            if (values != null && (values.get("accountId") instanceof Integer || values.get("accountId") instanceof Long)) {
                out.add(((Number) values.get("accountId")).longValue());
            }
        }
        return out;
    }

    /** Whether a JSON value is the whole number {@code accountId}. */
    private static boolean isAccount(Object value, long accountId) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == accountId;
    }

    /** Sets the key; whether that changed anything. */
    private static boolean put(Map<String, Object> values, String key, String value) {
        if (values.containsKey(key) && value.equals(values.get(key))) {
            return false;
        }
        values.put(key, value);
        return true;
    }

    /** The rule for a top-level {@code name} or {@code email} of {@code changes}; whether it changed. */
    private static boolean replace(Map<String, Object> changes, String key, String value) {
        Object old = changes.get(key);
        if (old == null) {
            return false;
        }
        if (old instanceof Map<?, ?> pair) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fromTo = new LinkedHashMap<>((Map<String, Object>) pair);
            boolean changed = false;
            for (String side : new String[] {"from", "to"}) {
                if (fromTo.containsKey(side)) {
                    changed |= put(fromTo, side, value);
                }
            }
            if (changed) {
                changes.put(key, fromTo);
            }
            return changed;
        }
        return put(changes, key, value);
    }

    /** The JSON object, with its keys in order; null for null, for text that is not JSON, and for non-objects. */
    private static Map<String, Object> object(String json) {
        if (json == null) {
            return null;
        }
        Object value;
        try {
            value = JSON.readValue(json, Object.class);
        } catch (JacksonException e) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) map);
        return out;
    }
}
