package io.popdesk.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Looks into the test context's database for what HTTP cannot show: the schema
 * ({@code information_schema}), locks ({@code pg_locks}), and the state of single rows.
 *
 * <p>Reads run on a read-only connection of their own, outside the app's pool. Do not use this to
 * create business data; fixtures go through the API ({@link Fixtures}). The one write it allows is
 * {@link #schemaFixture}, for tests about the schema or migration state itself.
 */
public final class DbInspector {

    /** One column from {@code information_schema.columns}. */
    public record Column(String name, String dataType, boolean nullable, String defaultValue) {}

    /** One advisory lock from {@code pg_locks}. For a lock taken with two int keys, objsubid is 2. */
    public record AdvisoryLock(long classid, long objid, int objsubid, boolean granted, int pid) {}

    private final PostgreSQLContainer postgres;

    public DbInspector(PostgreSQLContainer postgres) {
        this.postgres = postgres;
    }

    /** Base tables in the {@code public} schema, by name. */
    public List<String> tables() {
        return read(jdbc -> jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_type = 'BASE TABLE' order by table_name",
                String.class));
    }

    public List<Column> columns(String table) {
        return read(jdbc -> jdbc.query(
                "select column_name, data_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? order by ordinal_position",
                (rs, n) -> new Column(rs.getString(1), rs.getString(2), "YES".equals(rs.getString(3)),
                        rs.getString(4)),
                table));
    }

    /** Index names and definitions ({@code pg_indexes}). */
    public Map<String, String> indexes(String table) {
        return pairs("select indexname, indexdef from pg_indexes where schemaname = 'public' and tablename = ? "
                + "order by indexname", table);
    }

    /** Constraint names and definitions ({@code pg_get_constraintdef}). */
    public Map<String, String> constraints(String table) {
        return pairs("select c.conname, pg_get_constraintdef(c.oid) from pg_constraint c "
                + "join pg_class t on t.oid = c.conrelid join pg_namespace s on s.oid = t.relnamespace "
                + "where s.nspname = 'public' and t.relname = ? order by c.conname", table);
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
        try (Connection c = connect()) {
            c.setReadOnly(true);
            return work.apply(new JdbcTemplate(new SingleConnectionDataSource(c, true)));
        } catch (SQLException e) {
            throw new IllegalStateException("Database read failed", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Map<String, String> pairs(String sql, String table) {
        Map<String, String> out = new LinkedHashMap<>();
        read(jdbc -> jdbc.query(sql, (rs, n) -> out.put(rs.getString(1), rs.getString(2)), table));
        return out;
    }
}
