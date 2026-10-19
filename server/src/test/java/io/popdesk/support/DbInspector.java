package io.popdesk.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Looks into the test context's database for what HTTP cannot show: the schema
 * ({@code information_schema}), locks ({@code pg_locks}), and the state of single rows.
 *
 * <p>The schema is the one the app's own connections use: {@code current_schema()} of a connection
 * from the app's {@code DataSource}, read once. No schema name is written here. Every connection of
 * this class sets that schema, so unqualified table names in {@link #rows} and {@link #count} find
 * the app's tables.
 *
 * <p>Reads run on a read-only connection of their own, outside the app's pool, one per thread and
 * kept between reads. Do not use this to
 * create business data; fixtures go through the API ({@link Fixtures}). The one write it allows is
 * {@link #schemaFixture}, for tests about the schema or migration state itself.
 */
public final class DbInspector {

    /** One column from {@code information_schema.columns}. */
    public record Column(String name, String dataType, boolean nullable, String defaultValue) {}

    /** One advisory lock from {@code pg_locks}. For a lock taken with two int keys, objsubid is 2. */
    public record AdvisoryLock(long classid, long objid, int objsubid, boolean granted, int pid) {}

    private final PostgreSQLContainer postgres;
    private final DataSource appDataSource;
    private volatile String schema;

    private static final int VALID_SECONDS = 5;

    private final ThreadLocal<Connection> readConnection = new ThreadLocal<>();

    public DbInspector(PostgreSQLContainer postgres, DataSource appDataSource) {
        this.postgres = postgres;
        this.appDataSource = appDataSource;
    }

    /** The app's schema: {@code current_schema()} on a connection from the app's {@code DataSource}. */
    public String schema() {
        String known = schema;
        if (known != null) {
            return known;
        }
        try (Connection c = appDataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select current_schema()")) {
            if (!rs.next() || rs.getString(1) == null) {
                throw new IllegalStateException("The app's connection has no current schema");
            }
            schema = rs.getString(1);
            return schema;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the app's current schema", e);
        }
    }

    /** Base tables in the app's schema, by name. */
    public List<String> tables() {
        return read(jdbc -> jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = ? and table_type = 'BASE TABLE' order by table_name",
                String.class, schema()));
    }

    public List<Column> columns(String table) {
        return read(jdbc -> jdbc.query(
                "select column_name, data_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema = ? and table_name = ? order by ordinal_position",
                (rs, n) -> new Column(rs.getString(1), rs.getString(2), "YES".equals(rs.getString(3)),
                        rs.getString(4)),
                schema(), table));
    }

    /** Index names and definitions ({@code pg_indexes}). */
    public Map<String, String> indexes(String table) {
        return pairs("select indexname, indexdef from pg_indexes where schemaname = ? and tablename = ? "
                + "order by indexname", table);
    }

    /** Constraint names and definitions ({@code pg_get_constraintdef}). */
    public Map<String, String> constraints(String table) {
        return pairs("select c.conname, pg_get_constraintdef(c.oid) from pg_constraint c "
                + "join pg_class t on t.oid = c.conrelid join pg_namespace s on s.oid = t.relnamespace "
                + "where s.nspname = ? and t.relname = ? order by c.conname", table);
    }

    /** Advisory locks held or waited for right now, by any session. */
    public List<AdvisoryLock> advisoryLocks() {
        return read(jdbc -> jdbc.query(
                "select classid, objid, objsubid, granted, pid from pg_locks where locktype = 'advisory'",
                (rs, n) -> new AdvisoryLock(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getInt(5))));
    }

    /** Rows of a query, each as column name to value. */
    public List<Map<String, Object>> rows(String sql, Object... args) {
        return read(jdbc -> jdbc.query(sql, new ColumnMapRowMapper(), args));
    }

    /** The one row of a query. Fails with an AssertionError when there is none or more than one. */
    public Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        if (rows.size() != 1) {
            throw new AssertionError("Expected one row but got " + rows.size() + " from: " + sql);
        }
        return rows.getFirst();
    }

    /** The single number a query returns, for {@code select count(*) ...}. */
    public long count(String sql, Object... args) {
        Long n = read(jdbc -> jdbc.queryForObject(sql, Long.class, args));
        return n == null ? 0 : n;
    }

    /**
     * Runs SQL that changes the schema or the migration history, for tests about those (a table
     * without {@code tenant_id}, a migration version newer than the app). Never for business rows.
     */
    public void schemaFixture(String sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Schema fixture failed: " + sql, e);
        }
    }

    public String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    private <T> T read(Function<JdbcTemplate, T> work) {
        try {
            return work.apply(new JdbcTemplate(new SingleConnectionDataSource(readConnection(), true)));
        } catch (SQLException e) {
            throw new IllegalStateException("Database read failed", e);
        }
    }

    /**
     * This thread's read-only connection. Opening one per read made a helper that sends a query per
     * table and column take seconds, so each thread keeps one and reads go one after another on it.
     * It is opened again when it is closed or no longer answers (a test ended its backend, the
     * database went away). Autocommit stays on, so a read holds no transaction between calls.
     */
    private Connection readConnection() throws SQLException {
        Connection c = readConnection.get();
        if (c != null && c.isValid(VALID_SECONDS)) {
            return c;
        }
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // A broken connection; the new one replaces it.
            }
        }
        Properties props = new Properties();
        // No server-side prepared statements: a plan kept across calls could outlive a schema
        // fixture that changes a table, as a connection per read never did.
        props.setProperty("prepareThreshold", "0");
        Connection fresh = connect(props);
        fresh.setReadOnly(true);
        readConnection.set(fresh);
        return fresh;
    }

    private Connection connect() throws SQLException {
        return connect(new Properties());
    }

    private Connection connect(Properties props) throws SQLException {
        String appSchema = schema();
        props.setProperty("user", postgres.getUsername());
        props.setProperty("password", postgres.getPassword());
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), props);
        c.setSchema(appSchema);
        return c;
    }

    private Map<String, String> pairs(String sql, String table) {
        Map<String, String> out = new LinkedHashMap<>();
        read(jdbc -> jdbc.query(sql, (rs, n) -> out.put(rs.getString(1), rs.getString(2)), schema(), table));
        return out;
    }
}
