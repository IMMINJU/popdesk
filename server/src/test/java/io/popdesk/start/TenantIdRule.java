package io.popdesk.start;

import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schema half of the {@code tenant_id} gate (S0-AC-103): read what PostgreSQL says a schema
 * holds after migrations, and name every table outside the exceptions that lacks
 * {@code tenant_id bigint NOT NULL}.
 */
final class TenantIdRule {

    /**
     * The exceptions, one name at a time (architecture.md section 4). No patterns. The same list is
     * in {@code scripts/ci/check-tenant-id.py} and {@code server/README.md}; a test checks they agree.
     */
    static final Set<String> EXCEPTIONS = Set.of(
            "flyway_schema_history",
            "spring_session",
            "spring_session_attributes",
            "tenant");

    private TenantIdRule() {}

    /** Base tables of {@code schema} and their columns, from {@code information_schema}. */
    static Map<String, List<Column>> read(DbInspector db, String schema) {
        Map<String, List<Column>> tables = new LinkedHashMap<>();
        for (Map<String, Object> t : db.rows("select table_name from information_schema.tables "
                + "where table_schema = ? and table_type = 'BASE TABLE' order by table_name", schema)) {
            tables.put((String) t.get("table_name"), new ArrayList<>());
        }
        for (Map<String, Object> c : db.rows("select table_name, column_name, data_type, is_nullable, column_default "
                + "from information_schema.columns where table_schema = ? order by table_name, ordinal_position",
                schema)) {
            List<Column> columns = tables.get((String) c.get("table_name"));
            if (columns != null) {
                columns.add(new Column((String) c.get("column_name"), (String) c.get("data_type"),
                        "YES".equals(c.get("is_nullable")), (String) c.get("column_default")));
            }
        }
        return tables;
    }

    /** One message per table that breaks the rule; empty when the schema keeps it. */
    static List<String> violations(Map<String, List<Column>> tables) {
        List<String> out = new ArrayList<>();
        tables.forEach((table, columns) -> {
            if (EXCEPTIONS.contains(table)) {
                return;
            }
            Column tenantId = columns.stream().filter(c -> c.name().equals("tenant_id")).findFirst().orElse(null);
            if (tenantId == null) {
                out.add(table + ": no tenant_id");
            } else if (!tenantId.dataType().equals("bigint")) {
                out.add(table + ": tenant_id is " + tenantId.dataType() + ", not bigint");
            } else if (tenantId.nullable()) {
                out.add(table + ": tenant_id is nullable");
            }
        });
        return out;
    }
}
