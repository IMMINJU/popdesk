package io.popdesk.helpdesk.audit;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes audit rows (table {@code audit_log}). Rows are never changed once written. */
@Repository
class AuditRepository {

    private final JdbcClient jdbc;

    AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row; the JSON arguments are JSON text, or null for a field without a value. */
    void insert(String action, String actorJson, String targetJson, String changesJson, Instant occurredAt) {
        jdbc.sql("insert into audit_log (tenant_id, action, actor, target, changes, occurred_at) "
                        + "values (1, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)")
                .params(action, actorJson, targetJson, changesJson, Timestamp.from(occurredAt))
                .update();
    }
}
