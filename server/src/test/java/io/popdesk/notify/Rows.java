package io.popdesk.notify;

import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Wait;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the rows a request added, in any table of the app's schema, without knowing table or
 * column names: the slice does not fix the names of the send queue. A snapshot holds the primary
 * keys of every table; the rows added since are the keys that were not there.
 *
 * <p>Tests use this to catch a send queue row while the receiving side keeps failing, read all its
 * columns, and see it go when the send succeeds. Read-only, through {@link DbInspector}.
 */
final class Rows {

    /** Tables that Spring Session and Flyway own; never the send queue. */
    private static final Set<String> IGNORED = Set.of("spring_session", "spring_session_attributes",
            "flyway_schema_history");

    private static final Pattern PRIMARY_KEY = Pattern.compile("PRIMARY KEY \\((.+)\\)");

    /** Primary keys of every table at one moment. */
    record Snapshot(Map<String, Set<String>> keys) {}

    /** One row: its table, its primary key as text, and its values when it was read. */
    record Row(String table, String key, Map<String, Object> values) {

        /** The values that are instants ({@code timestamptz}, {@code timestamp}). */
        Map<String, Instant> instants() {
            Map<String, Instant> out = new LinkedHashMap<>();
            values.forEach((column, value) -> {
                Instant at = instant(value);
                if (at != null) {
                    out.put(column, at);
                }
            });
            return out;
        }

        /** The values that are whole numbers. */
        Map<String, Long> integers() {
            Map<String, Long> out = new LinkedHashMap<>();
            values.forEach((column, value) -> {
                if (value instanceof Integer || value instanceof Long || value instanceof Short) {
                    out.put(column, ((Number) value).longValue());
                }
            });
            return out;
        }

        /** Columns whose instant equals {@code at}. */
        Set<String> columnsAt(Instant at) {
            Set<String> out = new HashSet<>();
            instants().forEach((column, value) -> {
                if (value.equals(at)) {
                    out.add(column);
                }
            });
            return out;
        }

        /** Columns whose whole number equals {@code n}. */
        Set<String> columnsEqualTo(long n) {
            Set<String> out = new HashSet<>();
            integers().forEach((column, value) -> {
                if (value == n) {
                    out.add(column);
                }
            });
            return out;
        }

        @Override
        public String toString() {
            return table + "[" + key + "] " + values;
        }
    }

    private final DbInspector db;
    private final Map<String, List<String>> keyColumns = new LinkedHashMap<>();

    Rows(DbInspector db) {
        this.db = db;
    }

    Snapshot snapshot() {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        for (String table : tables()) {
            keys.put(table, keys(table));
        }
        return new Snapshot(keys);
    }

    /** Rows that are in the database now and were not in {@code before}, read now. */
    List<Row> added(Snapshot before) {
        List<Row> out = new ArrayList<>();
        for (String table : tables()) {
            Set<String> earlier = before.keys().getOrDefault(table, Set.of());
            for (String key : keys(table)) {
                if (!earlier.contains(key)) {
                    Row row = read(table, key);
                    if (row != null) {
                        out.add(row);
                    }
                }
            }
        }
        return out;
    }

    /** The row as it is now, or null when it is gone. */
    Row reread(Row row) {
        return read(row.table(), row.key());
    }

    boolean exists(Row row) {
        return db.count("select count(*) from " + quote(row.table()) + " where " + keyExpression(row.table())
                + " = ?", row.key()) > 0;
    }

    /** Waits until at least one of {@code rows} is gone, and returns the ones that are gone. */
    List<Row> awaitGone(String what, List<Row> rows) {
        return awaitGone(what, rows, Wait.DEFAULT);
    }

    List<Row> awaitGone(String what, List<Row> rows, Duration atMost) {
        return Wait.until(what, atMost, () -> {
            List<Row> gone = rows.stream().filter(r -> !exists(r)).toList();
            return gone.isEmpty() ? null : gone;
        });
    }

    /** The one row of {@code rows} that has an instant equal to {@code at}. */
    static Row withInstant(List<Row> rows, Instant at) {
        List<Row> found = rows.stream().filter(r -> !r.columnsAt(at).isEmpty()).toList();
        if (found.size() != 1) {
            throw new AssertionError("Expected one added row with a time of " + at + " (the next attempt) but got "
                    + found.size() + ". Added rows: " + rows);
        }
        return found.getFirst();
    }

    /**
     * Columns of the row, as stored now, whose value contains {@code needle}, ignoring case. Text
     * columns are compared as text (JSON too); {@code bytea} columns as UTF-8 bytes, ignoring case
     * where the bytes are ASCII.
     */
    List<String> columnsContaining(Row row, String needle) {
        List<String> found = new ArrayList<>();
        String where = keyExpression(row.table()) + " = ?";
        for (Column column : db.columns(row.table())) {
            String col = quote(column.name());
            long n;
            if (column.dataType().equals("bytea")) {
                n = db.count("select count(*) from " + quote(row.table()) + " where " + where
                        + " and (position(convert_to(?, 'UTF8') in " + col + ") > 0"
                        + " or position(lower(?) in lower(encode(" + col + ", 'escape'))) > 0)",
                        row.key(), needle, needle);
            } else {
                n = db.count("select count(*) from " + quote(row.table()) + " where " + where
                        + " and position(lower(?) in lower(" + col + "::text)) > 0", row.key(), needle);
            }
            if (n > 0) {
                found.add(column.name());
            }
        }
        return found;
    }

    private List<String> tables() {
        return db.tables().stream().filter(t -> !IGNORED.contains(t)).filter(t -> !keyColumns(t).isEmpty()).toList();
    }

    private Set<String> keys(String table) {
        return new HashSet<>(db.rows("select " + keyExpression(table) + " as k from " + quote(table)).stream()
                .map(r -> String.valueOf(r.get("k")))
                .toList());
    }

    private Row read(String table, String key) {
        List<Map<String, Object>> found = db.rows("select * from " + quote(table) + " where " + keyExpression(table)
                + " = ?", key);
        return found.isEmpty() ? null : new Row(table, key, found.getFirst());
    }

    private String keyExpression(String table) {
        List<String> columns = keyColumns(table);
        if (columns.size() == 1) {
            return quote(columns.getFirst()) + "::text";
        }
        return "concat_ws('|', " + String.join(", ", columns.stream().map(c -> quote(c) + "::text").toList()) + ")";
    }

    private synchronized List<String> keyColumns(String table) {
        return keyColumns.computeIfAbsent(table, t -> {
            for (String definition : db.constraints(t).values()) {
                Matcher m = PRIMARY_KEY.matcher(definition);
                if (m.find()) {
                    List<String> columns = new ArrayList<>();
                    for (String c : m.group(1).split(",")) {
                        columns.add(c.strip().replace("\"", ""));
                    }
                    return columns;
                }
            }
            return List.of();
        });
    }

    static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Instant i) {
            return i;
        }
        return null;
    }

    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
