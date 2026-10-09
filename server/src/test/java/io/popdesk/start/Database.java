package io.popdesk.start;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One database of a {@link StartPostgres} container and the role the app logs in with. Reads go
 * through the superuser and look at every schema the app may have made, so they work without
 * knowing the app's schema or table names. A table that does not exist yet simply has no rows.
 */
final class Database {

    /** Tables that are not business tables (architecture.md section 4). */
    static final Set<String> NOT_BUSINESS = Set.of("flyway_schema_history", "spring_session",
            "spring_session_attributes", "tenant");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A table and the schema it is in. */
    record Table(String schema, String name) {

        String qualified() {
            return quote(schema) + "." + quote(name);
        }
    }

    /** One row, as {@code row_to_json} text and parsed. */
    record Row(Table table, String text, JsonNode json) {

        boolean contains(String value) {
            return text.contains(value);
        }

        /** Whether some text value in the row, at any depth, is exactly {@code value}. */
        boolean hasText(String value) {
            return nodes(json).stream().anyMatch(n -> n.isString() && n.stringValue().equals(value));
        }

        /** Whether some object in the row, at any depth, has {@code accountId} equal to {@code id}. */
        boolean hasAccountId(long id) {
            return nodes(json).stream().anyMatch(n -> n.isObject() && n.path("accountId").isIntegralNumber()
                    && n.path("accountId").asLong() == id);
        }

        @Override
        public String toString() {
            return table.name() + " " + text;
        }
    }

    /** An advisory lock in this database ({@code pg_locks}). Two int keys give objsubid 2. */
    record Lock(long classid, long objid, int objsubid, boolean granted, int pid) {}

    private final StartPostgres postgres;
    private final String name;
    private final String user;
    private final String password;

    Database(StartPostgres postgres, String name, String user, String password) {
        this.postgres = postgres;
        this.name = name;
        this.user = user;
        this.password = password;
    }

    String name() {
        return name;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    /** {@code POPDESK_DB_URL} for the app, with the flag configuration.md asks to keep. */
    String appUrl() {
        return postgres.jdbcUrl(name) + "?logServerErrorDetail=false";
    }

    /** Base tables in every schema but PostgreSQL's own. */
    List<Table> tables() {
        return query("select table_schema, table_name from information_schema.tables "
                + "where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema') "
                + "and table_schema not like 'pg_toast%' order by table_schema, table_name",
                rs -> new Table(rs.getString(1), rs.getString(2)));
    }

    Optional<Table> table(String tableName) {
        return tables().stream().filter(t -> t.name().equals(tableName)).findFirst();
    }

    /** Every row of every business table. */
    List<Row> businessRows() {
        List<Row> out = new ArrayList<>();
        for (Table table : tables()) {
            if (!NOT_BUSINESS.contains(table.name())) {
                out.addAll(rows(table));
            }
        }
        return out;
    }

    List<Row> rows(Table table) {
        return query("select row_to_json(t)::text from " + table.qualified() + " t",
                rs -> new Row(table, rs.getString(1), parse(rs.getString(1))));
    }

    /** Business rows that contain every one of {@code values}. */
    List<Row> rowsContaining(String... values) {
        return businessRows().stream().filter(r -> {
            for (String v : values) {
                if (!r.contains(v)) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    /** Account rows: business rows that hold an Argon2id password hash (S0-AC-28). */
    List<Row> accountRows() {
        return rowsContaining("$argon2id$");
    }

    /** Rows of the first admin's audit entry: a text value {@code FIRST_ADMIN_CREATED}. */
    List<Row> firstAdminAuditRows() {
        return businessRows().stream().filter(r -> r.hasText("FIRST_ADMIN_CREATED")).toList();
    }

    /** Advisory locks held or waited for in this database. */
    List<Lock> advisoryLocks() {
        return query("select l.classid, l.objid, l.objsubid, l.granted, l.pid from pg_locks l "
                        + "join pg_database d on d.oid = l.database "
                        + "where l.locktype = 'advisory' and d.datname = current_database()",
                rs -> new Lock(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBoolean(4), rs.getInt(5)));
    }

    /** The granted advisory locks taken with two int keys. */
    List<Lock> twoKeyLocks() {
        return advisoryLocks().stream().filter(l -> l.objsubid() == 2 && l.granted()).toList();
    }

    /** {@code pg_stat_activity.state} of a backend, or null when it is gone. */
    String backendState(int pid) {
        List<String> states = query("select state from pg_stat_activity where pid = " + pid, rs -> rs.getString(1));
        return states.isEmpty() ? null : states.getFirst();
    }

    /** Whether a query containing {@code fragment} is running in this database (not this check itself). */
    boolean running(String fragment) {
        return !query("select pid from pg_stat_activity where datname = current_database() and state = 'active' "
                + "and pid <> pg_backend_pid() and position('" + fragment.replace("'", "''") + "' in query) > 0",
                rs -> rs.getInt(1)).isEmpty();
    }

    /** Ends one backend, as a dropped connection would. */
    void terminate(int pid) {
        execute("select pg_terminate_backend(" + pid + ")");
    }

    /** Ends every other backend of this database, to free an app that hangs in it. */
    void terminateAll() {
        execute("select pg_terminate_backend(pid) from pg_stat_activity "
                + "where datname = current_database() and pid <> pg_backend_pid()");
    }

    /** Runs SQL as the superuser: schema and migration-history fixtures only, never business rows. */
    void execute(String sql) {
        try (Connection c = postgres.superuserConnection(name); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Fixture SQL failed: " + sql, e);
        }
    }

    <T> List<T> query(String sql, RowReader<T> reader) {
        try (Connection c = postgres.superuserConnection(name);
                PreparedStatement s = c.prepareStatement(sql);
                ResultSet rs = s.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) {
                out.add(reader.read(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Database read failed: " + sql, e);
        }
    }

    @FunctionalInterface
    interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    @Override
    public String toString() {
        return name;
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("row_to_json gave text that is not JSON: " + text, e);
        }
    }

    /** Every node at any depth; a text value holding a JSON object or array is read as JSON too. */
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

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
