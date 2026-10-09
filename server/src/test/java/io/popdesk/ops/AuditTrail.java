package io.popdesk.ops;

import io.popdesk.support.DbInspector;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds audit rows without knowing the audit table's name or columns: the slice fixes neither.
 *
 * <p>Every business table (all tables but the named exceptions of {@code architecture.md} section 4)
 * is read as {@code row_to_json}. A row "contains" a value when its JSON text does. An action's
 * audit rows are the rows that contain the needles, in tables that gained such rows, and that were
 * not there before the action: {@link #mark} before, {@link #newRows} after. A row that only changed
 * (an account's failed sign-in count) does not count, because its table gained no row.
 *
 * <p>JSON text stored in a text column is read as JSON too, so the checks below work whether the
 * row keeps the person in a {@code jsonb} column or in a text column holding JSON.
 */
final class AuditTrail {

    /** Tables that are not business tables (architecture.md section 4). */
    static final Set<String> NOT_BUSINESS = Set.of("flyway_schema_history", "spring_session",
            "spring_session_attributes", "tenant");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One row of a table, as JSON. */
    record Row(String table, String text, JsonNode json) {

        boolean contains(String value) {
            return text.contains(value);
        }

        /** Whether some value in the row, at any depth, is the number or the text {@code n}. */
        boolean mentions(long n) {
            return values(json).stream().anyMatch(v -> (v.isIntegralNumber() && v.asLong() == n)
                    || (v.isString() && v.stringValue().equals(Long.toString(n))));
        }

        /** The objects in the row, at any depth, whose fields are exactly accountId, name, email. */
        List<JsonNode> people() {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode node : nodes(json)) {
                if (node.isObject()) {
                    Set<String> names = new TreeSet<>();
                    node.propertyNames().forEach(names::add);
                    if (names.equals(Set.of("accountId", "email", "name"))) {
                        out.add(node);
                    }
                }
            }
            return out;
        }

        /** The person object for this account, if the row has one with these exact values. */
        Optional<JsonNode> person(long accountId, String name, String email) {
            return people().stream()
                    .filter(p -> p.path("accountId").isIntegralNumber() && p.path("accountId").asLong() == accountId)
                    .filter(p -> name.equals(p.path("name").asString(null)))
                    .filter(p -> email.equals(p.path("email").asString(null)))
                    .findFirst();
        }

        /** Every timestamp in the row, at any depth, at microsecond precision. */
        List<Instant> times() {
            List<Instant> out = new ArrayList<>();
            for (JsonNode v : values(json)) {
                if (v.isString()) {
                    try {
                        out.add(OffsetDateTime.parse(v.stringValue()).toInstant().truncatedTo(ChronoUnit.MICROS));
                    } catch (DateTimeParseException notATime) {
                        // Not a timestamp; other values are checked by other methods.
                    }
                }
            }
            return out;
        }

        @Override
        public String toString() {
            return table + " " + text;
        }
    }

    /** The rows that contained the needles before an action, per table. */
    record Mark(List<String> needles, Map<String, Set<String>> before) {}

    private final DbInspector db;

    AuditTrail(DbInspector db) {
        this.db = db;
    }

    /** Business tables of the app's schema. */
    List<String> businessTables() {
        return db.tables().stream().filter(t -> !NOT_BUSINESS.contains(t)).toList();
    }

    /** Rows of business tables whose JSON contains every needle. */
    List<Row> rowsContaining(String... needles) {
        List<Row> out = new ArrayList<>();
        for (String table : businessTables()) {
            for (String text : texts(table, List.of(needles))) {
                out.add(new Row(table, text, parse(text)));
            }
        }
        return out;
    }

    Mark mark(String... needles) {
        Map<String, Set<String>> before = new LinkedHashMap<>();
        for (String table : businessTables()) {
            before.put(table, new HashSet<>(texts(table, List.of(needles))));
        }
        return new Mark(List.of(needles), before);
    }

    /** Rows with the mark's needles that are new since the mark, in tables that gained such rows. */
    List<Row> newRows(Mark mark) {
        List<Row> out = new ArrayList<>();
        for (String table : businessTables()) {
            List<String> now = texts(table, mark.needles());
            Set<String> before = mark.before().getOrDefault(table, Set.of());
            if (now.size() <= before.size()) {
                continue;
            }
            for (String text : now) {
                if (!before.contains(text)) {
                    out.add(new Row(table, text, parse(text)));
                }
            }
        }
        return out;
    }

    private List<String> texts(String table, List<String> needles) {
        String where = needles.stream().map(n -> "position(? in row_to_json(t)::text) > 0")
                .collect(Collectors.joining(" and "));
        return db.rows("select row_to_json(t)::text as j from " + quote(table) + " t"
                        + (needles.isEmpty() ? "" : " where " + where), needles.toArray())
                .stream().map(r -> (String) r.get("j")).toList();
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("row_to_json gave text that is not JSON: " + text, e);
        }
    }

    /** Every node at any depth; a text value that holds a JSON object or array is read as JSON. */
    private static List<JsonNode> nodes(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        out.add(node);
        if (node.isString()) {
            String s = node.stringValue().strip();
            if (s.startsWith("{") || s.startsWith("[")) {
                try {
                    collect(JSON.readTree(s), out);
                } catch (JacksonException notJson) {
                    // Text that only looks like JSON.
                }
            }
        }
        for (JsonNode child : node.values()) {
            collect(child, out);
        }
    }

    private static List<JsonNode> values(JsonNode root) {
        return nodes(root).stream().filter(n -> n.isValueNode()).toList();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
