package io.popdesk.support;

import java.util.UUID;

/**
 * Makes a write of the app fail in the database, so a test decides where a transaction breaks.
 *
 * <p>A {@code test_} trigger on one table raises an exception for the rows that hold the marker: a
 * row matches when one of its columns, read as text, equals the marker, in the old or the new row.
 * {@link #statement} fails the statement that writes the row. {@link #commit} lets the insert
 * through and fails when the transaction commits (a deferred constraint trigger), after everything
 * else was written.
 *
 * <p>{@link #close()} drops the trigger; use it in try-with-resources. For a write that should wait
 * instead of fail, see {@link HeldWrite}.
 */
public final class FailedWrite implements AutoCloseable {

    private static final String FUNCTION = "test_popdesk_failed_write";

    private final DbInspector db;
    private final String dropTrigger;

    private FailedWrite(DbInspector db, String table, String createTrigger, String marker) {
        this.db = db;
        db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                + "declare old_row jsonb; new_row jsonb;\n"
                + "begin\n"
                + "  if TG_OP <> 'INSERT' then old_row := to_jsonb(OLD); end if;\n"
                + "  if TG_OP <> 'DELETE' then new_row := to_jsonb(NEW); end if;\n"
                + "  if exists (select 1 from jsonb_each_text(old_row) e where e.value = TG_ARGV[0])\n"
                + "      or exists (select 1 from jsonb_each_text(new_row) e where e.value = TG_ARGV[0]) then\n"
                + "    raise exception 'test_ trigger: injected failure';\n"
                + "  end if;\n"
                + "  if TG_OP = 'DELETE' then return OLD; end if;\n"
                + "  return NEW;\n"
                + "end $f$");
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        dropTrigger = "drop trigger if exists " + name + " on " + quote(table);
        db.schemaFixture(createTrigger.formatted(name, quote(table), FUNCTION + "(" + literal(marker) + ")"));
    }

    /**
     * The statement that writes a row holding the marker fails.
     *
     * @param table the table, as the app's schema names it
     * @param events the events of the trigger, as SQL: {@code insert}, {@code delete}, {@code insert or update}
     * @param marker a column value of the rows whose write fails
     */
    public static FailedWrite statement(DbInspector db, String table, String events, String marker) {
        return new FailedWrite(db, table, "create trigger %s before " + events + " on %s for each row "
                + "execute function %s", marker);
    }

    /** A row holding the marker is inserted, and the commit of its transaction fails. */
    public static FailedWrite commit(DbInspector db, String table, String marker) {
        return new FailedWrite(db, table, "create constraint trigger %s after insert on %s deferrable initially "
                + "deferred for each row execute function %s", marker);
    }

    @Override
    public void close() {
        db.schemaFixture(dropTrigger);
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
