package io.popdesk.helpdesk.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log (AUDIT-1, S0-AC-83). A row is written in the transaction of the change it records, so
 * both are kept or neither is. The person is always {@code {accountId, name, email}}.
 */
@Service
public class AuditLog {

    /** The account ID that stands for popdesk itself (the first admin, commands). */
    public static final long SYSTEM_ACCOUNT_ID = 0;

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
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("accountId", SYSTEM_ACCOUNT_ID);
        actor.put("name", null);
        actor.put("email", null);
        // The column keeps microseconds (timestamptz).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repository.insert(action, json.writeValueAsString(actor), toJson(target), toJson(changes), now);
    }

    private String toJson(Map<String, ?> value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
