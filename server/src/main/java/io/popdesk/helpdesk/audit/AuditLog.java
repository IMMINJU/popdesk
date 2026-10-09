package io.popdesk.helpdesk.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log (AUDIT-1, S0-AC-83). A row is written in the transaction of the change it records, so
 * both are kept or neither is. The person is always {@code {accountId, name, email}}. Never pass a
 * password, a token, or a link.
 *
 * <p>A row holds exactly the inputs of {@link CanonicalAuditJson#encode}: {@code action}, the person,
 * {@code target}, {@code changes}, and the time at microseconds. Instants inside {@code target} and
 * {@code changes} are stored in the canonical text form, so slice 1 can read a row back and get the
 * same bytes.
 */
@Service
public class AuditLog {

    /** The account ID that stands for popdesk itself (the first admin, commands). */
    public static final long SYSTEM_ACCOUNT_ID = 0;

    /** The person who did something. {@code name} and {@code email} are null for the system. */
    public record Actor(long accountId, String name, String email) {

        public static final Actor SYSTEM = new Actor(SYSTEM_ACCOUNT_ID, null, null);
    }

    private final AuditRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    AuditLog(AuditRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    /** Records an action done by popdesk itself: the person is account 0 without name or email. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBySystem(String action, Map<String, ?> target, Map<String, ?> changes) {
        record(Actor.SYSTEM, action, target, changes);
    }

    /** Records an action in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, Map<String, ?> target, Map<String, ?> changes) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("accountId", actor.accountId());
        person.put("name", actor.name());
        person.put("email", actor.email());
        // The column keeps microseconds (timestamptz).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repository.insert(action, json.writeValueAsString(person), toJson(target), toJson(changes), now);
    }

    private String toJson(Map<String, ?> value) {
        return value == null ? null : json.writeValueAsString(canonicalTimes(value));
    }

    private static Object canonicalTimes(Object value) {
        return switch (value) {
            case Instant instant -> CanonicalAuditJson.time(instant);
            case Map<?, ?> map -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(k, v == null ? null : canonicalTimes(v)));
                yield out;
            }
            case Collection<?> list -> {
                List<Object> out = new ArrayList<>();
                list.forEach(v -> out.add(v == null ? null : canonicalTimes(v)));
                yield out;
            }
            default -> value;
        };
    }
}
