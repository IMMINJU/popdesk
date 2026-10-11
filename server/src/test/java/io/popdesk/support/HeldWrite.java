package io.popdesk.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Keeps a write of the app waiting in the database until the test lets it go on, so a test decides
 * the order of two requests instead of leaving it to chance.
 *
 * <p>A {@code test_} trigger on one table, before the given events, waits while this object holds an
 * advisory lock on a connection of its own (at most 20 seconds). The statement waits inside its
 * transaction, with the locks it took so far. It waits in short {@code pg_sleep} calls, not on a
 * lock, so the connection's {@code lock_timeout} does not end it, and it shows in
 * {@code pg_stat_activity} with {@code wait_event = 'PgSleep'}.
 *
 * <p>Only rows that hold the marker are held: a row matches when one of its columns, read as text,
 * equals the marker, in the old or the new row. An empty marker holds every row. {@link #close()}
 * releases the lock and drops the trigger; use it in try-with-resources.
 */
public final class HeldWrite implements AutoCloseable {

    private static final String FUNCTION = "test_popdesk_held_write";

    private final DbInspector db;
    private final Connection connection;
    private final long key = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
    private final String dropTrigger;
    private boolean held;

    /**
     * Starts holding.
     *
     * @param table the table, as the app's schema names it
     * @param events the events of the trigger, as SQL: {@code insert}, {@code update}, {@code insert or update}
     * @param marker a column value of the rows to hold, or an empty text for every row
     */
    public HeldWrite(DbInspector db, PostgreSQLContainer postgres, String table, String events, String marker) {
        this.db = db;
        db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                + "declare old_row jsonb; new_row jsonb;\n"
                + "begin\n"
                + "  if TG_OP <> 'INSERT' then old_row := to_jsonb(OLD); end if;\n"
                + "  if TG_OP <> 'DELETE' then new_row := to_jsonb(NEW); end if;\n"
                + "  if TG_ARGV[0] = ''\n"
                + "      or exists (select 1 from jsonb_each_text(old_row) e where e.value = TG_ARGV[0])\n"
                + "      or exists (select 1 from jsonb_each_text(new_row) e where e.value = TG_ARGV[0]) then\n"
                + "    for i in 1..400 loop\n"
                + "      exit when not exists (select 1 from pg_locks l where l.locktype = 'advisory' and l.granted\n"
                + "        and l.classid = 0 and l.objsubid = 1 and l.objid = TG_ARGV[1]::bigint::oid);\n"
                + "      perform pg_sleep(0.05);\n"
                + "    end loop;\n"
                + "  end if;\n"
                + "  if TG_OP = 'DELETE' then return OLD; end if;\n"
                + "  return NEW;\n"
                + "end $f$");
        try {
            connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(), postgres.getPassword());
            run("select pg_advisory_lock(?)");
            held = true;
        } catch (SQLException e) {
            throw new AssertionError("Could not take the advisory lock of a held write", e);
        }
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        dropTrigger = "drop trigger if exists " + name + " on " + quote(table);
        db.schemaFixture("create trigger " + name + " before " + events + " on " + quote(table)
                + " for each row execute function " + FUNCTION + "(" + literal(marker) + ", '" + key + "')");
    }

    /** Waits until a statement of the app is held in the trigger. */
    public void awaitHeld(String what) {
        Wait.until(what + " held in the trigger", () -> {
            var found = db.rows("select pid from pg_stat_activity where wait_event = 'PgSleep' "
                    + "and pid <> pg_backend_pid() and datname = current_database()");
            return found.size() == 1 ? found.getFirst() : null;
        });
    }

    /** How many statements wait for a lock right now, in this database. */
    public long waitingForALock() {
        return db.count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                + "and pid <> pg_backend_pid() and datname = current_database()");
    }

    /** Lets the held statement go on. */
    public void release() {
        if (held) {
            try {
                run("select pg_advisory_unlock(?)");
                held = false;
            } catch (SQLException e) {
                throw new AssertionError("Could not release the advisory lock of a held write", e);
            }
        }
    }

    private void run(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.executeQuery();
        }
    }

    /** Closing the connection lets a statement that still waits go on, so the trigger can be dropped. */
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new AssertionError("Could not close the connection of a held write", e);
        } finally {
            db.schemaFixture(dropTrigger);
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
