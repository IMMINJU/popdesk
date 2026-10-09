package io.popdesk.deploy;

import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Looks into a popdesk database over JDBC, without knowing table or column names (the slice fixes
 * neither): the schema is the one that holds {@code flyway_schema_history}, business tables are all
 * its tables but the named exceptions of {@code architecture.md} section 4, and rows are found by
 * their {@code row_to_json} text. For what HTTP cannot show: row counts, audit rows, the advisory
 * lock, whether a database is empty. Never used to create business data.
 */
final class Db {

    /** Tables that are not business tables (architecture.md section 4). */
    static final Set<String> NOT_BUSINESS = Set.of("flyway_schema_history", "spring_session",
            "spring_session_attributes", "tenant");

    /** Spring Session's tables: they change on every request, so snapshots leave them out. */
    static final Set<String> SESSION_TABLES = Set.of("spring_session", "spring_session_attributes");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One row of a table, as JSON text. */
    record Row(String table, String text) {

        JsonNode json() {
            try {
                return JSON.readTree(text);
            } catch (JacksonException e) {
                throw new AssertionError("row_to_json gave text that is not JSON: " + text, e);
            }
        }

        /** Objects in the row, at any depth, whose fields are exactly accountId, name, email. */
        List<JsonNode> people() {
            List<JsonNode> out = new ArrayList<>();
            collect(json(), out);
            return out.stream().filter(n -> {
                if (!n.isObject()) {
                    return false;
                }
                Set<String> names = new TreeSet<>();
                n.propertyNames().forEach(names::add);
                return names.equals(Set.of("accountId", "email", "name"));
            }).toList();
        }

        /** Whether some person object in the row has account ID {@code id}. */
        boolean hasPerson(long id) {
            return people().stream().anyMatch(p -> p.path("accountId").isIntegralNumber()
                    && p.path("accountId").asLong() == id);
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

        @Override
        public String toString() {
            return table + " " + text;
        }
    }

    private final IntSupplier port;
    private final String host;
    private final String database;
    private final String user;
    private final String password;

    Db(IntSupplier port, String database, String user, String password) {
        this("127.0.0.1", port, database, user, password);
    }

    Db(String host, IntSupplier port, String database, String user, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    /** A new connection. Fails (an assertion) when the database does not answer. */
    Connection connect() {
        String url = "jdbc:postgresql://" + host + ":" + port.getAsInt() + "/" + database;
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new AssertionError("Could not connect to " + url + " as " + user + ": " + e.getMessage(), e);
        }
    }

    /** Waits until the database accepts connections. */
    void awaitReady() {
        Wait.untilAsserted("database accepts connections", Duration.ofMinutes(2), () -> {
            try (Connection c = connect()) {
                query(c, "select 1");
            }
        });
    }

    /** The schema that holds {@code flyway_schema_history}: popdesk's schema. */
    String schema() {
        List<Map<String, Object>> rows = query("select table_schema from information_schema.tables "
                + "where table_name = 'flyway_schema_history' and table_schema not in ('pg_catalog', 'information_schema')");
        if (rows.size() != 1) {
            throw new AssertionError("Expected one schema with flyway_schema_history but found " + rows);
        }
        return (String) rows.getFirst().get("table_schema");
    }

    List<String> tables() {
        return query("select table_name from information_schema.tables where table_schema = ? "
                + "and table_type = 'BASE TABLE' order by table_name", schema())
                .stream().map(r -> (String) r.get("table_name")).toList();
    }

    List<String> businessTables() {
        return tables().stream().filter(t -> !NOT_BUSINESS.contains(t)).toList();
    }

    /** Base tables outside the system schemas: 0 for an empty database. */
    long userTableCount() {
        return ((Number) query("select count(*) as n from information_schema.tables where table_type = 'BASE TABLE' "
                + "and table_schema not in ('pg_catalog', 'information_schema')").getFirst().get("n")).longValue();
    }

    /** Rows per table of popdesk's schema, leaving out Spring Session's tables. */
    Map<String, Long> counts() {
        Map<String, Long> out = new TreeMap<>();
        String schema = schema();
        for (String table : tables()) {
            if (!SESSION_TABLES.contains(table)) {
                out.put(table, count("select count(*) from " + quote(schema) + "." + quote(table)));
            }
        }
        return out;
    }

    /** Every row of popdesk's schema as JSON text, per table, leaving out Spring Session's tables. */
    Map<String, List<String>> snapshot() {
        Map<String, List<String>> out = new TreeMap<>();
        String schema = schema();
        for (String table : tables()) {
            if (!SESSION_TABLES.contains(table)) {
                out.put(table, query("select row_to_json(t)::text as j from " + quote(schema) + "." + quote(table)
                        + " t order by 1").stream().map(r -> (String) r.get("j")).toList());
            }
        }
        return out;
    }

    /** Rows of business tables whose JSON text contains {@code needle}. */
    List<Row> rowsContaining(String needle) {
        List<Row> out = new ArrayList<>();
        String schema = schema();
        for (String table : businessTables()) {
            for (Map<String, Object> r : query("select row_to_json(t)::text as j from " + quote(schema) + "."
                    + quote(table) + " t where position(? in row_to_json(t)::text) > 0", needle)) {
                out.add(new Row(table, (String) r.get("j")));
            }
        }
        return out;
    }

    long count(String sql, Object... args) {
        return ((Number) query(sql, args).getFirst().values().iterator().next()).longValue();
    }

    String serverVersion() {
        return (String) query("show server_version").getFirst().values().iterator().next();
    }

    List<Map<String, Object>> query(String sql, Object... args) {
        try (Connection c = connect()) {
            return query(c, sql, args);
        } catch (SQLException e) {
            throw new AssertionError(sql + ": " + e.getMessage(), e);
        }
    }

    void execute(String sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new AssertionError(sql + ": " + e.getMessage(), e);
        }
    }

    static List<Map<String, Object>> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            if (!s.execute()) {
                return rows;
            }
            try (ResultSet rs = s.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
