package io.popdesk.notify;

import io.popdesk.support.DbInspector;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Makes a request's transaction fail when it commits, with a trigger whose name starts with
 * {@code test_} (tasks.md 6절, 7절 "같은 트랜잭션 기준"): a deferred constraint trigger on one table
 * that raises for a new or changed row with a column whose text equals the test's marker. The
 * request's other statements, the queue row among them, have all run by then, so the trigger tells
 * whether they go with the failed commit. An empty marker fails every row of the table.
 *
 * <p>{@link #close()} drops the triggers this object made; use it in try-with-resources.
 */
final class CommitFailure implements AutoCloseable {

    private static final String FUNCTION = "test_popdesk_fail_at_commit";

    private final DbInspector db;
    private final List<String> dropStatements = new ArrayList<>();

    CommitFailure(DbInspector db) {
        this.db = db;
        db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                + "begin\n"
                + "  if TG_ARGV[0] = ''\n"
                + "     or exists (select 1 from jsonb_each_text(to_jsonb(NEW)) e where e.value = TG_ARGV[0]) then\n"
                + "    raise exception 'test_ trigger: injected failure at commit';\n"
                + "  end if;\n"
                + "  return NEW;\n"
                + "end $f$");
    }

    /** Fails the commit of a transaction that inserts or updates a row of {@code table} holding {@code marker}. */
    void failAtCommit(String table, String marker) {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        db.schemaFixture("create constraint trigger " + name + " after insert or update on " + Rows.quote(table)
                + " deferrable initially deferred for each row execute function " + FUNCTION + "('"
                + marker.replace("'", "''") + "')");
        dropStatements.add("drop trigger if exists " + name + " on " + Rows.quote(table));
    }

    @Override
    public void close() {
        for (String drop : dropStatements) {
            db.schemaFixture(drop);
        }
        dropStatements.clear();
    }
}
