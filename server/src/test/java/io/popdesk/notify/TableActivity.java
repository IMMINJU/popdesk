package io.popdesk.notify;

import io.popdesk.support.DbInspector;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rows inserted and deleted per table, from PostgreSQL's statistics ({@code pg_stat_user_tables}).
 * For queue rows that come and go faster than a test can read them, as when the app has no SMTP and
 * handles a mail row at once.
 *
 * <p>A server process reports its counts when its transaction ends, but may hold them back for up to
 * about 10 seconds while it is busy; wait for changes, do not read them once.
 */
final class TableActivity {

    /** Spring Session's tables come and go with every sign-in; never the send queue. */
    private static final String SESSION_PREFIX = "spring_session";

    /** Counts of one table: rows ever inserted, rows ever deleted, rows now. */
    record Counts(long inserted, long deleted, long rows) {}

    private final DbInspector db;

    TableActivity(DbInspector db) {
        this.db = db;
    }

    /** Counts of every table in the app's schema but the session tables. */
    Map<String, Counts> counts() {
        Map<String, Counts> out = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("select relname, n_tup_ins, n_tup_del from pg_stat_user_tables "
                + "where schemaname = ? order by relname", db.schema())) {
            String table = (String) r.get("relname");
            if (table.startsWith(SESSION_PREFIX)) {
                continue;
            }
            long now = db.count("select count(*) from " + Rows.quote(table));
            out.put(table, new Counts(((Number) r.get("n_tup_ins")).longValue(),
                    ((Number) r.get("n_tup_del")).longValue(), now));
        }
        return out;
    }

    /** Tables that had rows inserted and rows deleted between {@code before} and {@code after}. */
    static Map<String, Counts> insertedAndDeleted(Map<String, Counts> before, Map<String, Counts> after) {
        Map<String, Counts> out = new LinkedHashMap<>();
        after.forEach((table, now) -> {
            Counts then = before.getOrDefault(table, new Counts(0, 0, 0));
            long inserted = now.inserted() - then.inserted();
            long deleted = now.deleted() - then.deleted();
            if (inserted > 0 && deleted > 0) {
                out.put(table, new Counts(inserted, deleted, now.rows()));
            }
        });
        return out;
    }

    /** Tables that had any row deleted between {@code before} and {@code after}. */
    static Map<String, Long> deleted(Map<String, Counts> before, Map<String, Counts> after) {
        Map<String, Long> out = new LinkedHashMap<>();
        after.forEach((table, now) -> {
            long deleted = now.deleted() - before.getOrDefault(table, new Counts(0, 0, 0)).deleted();
            if (deleted > 0) {
                out.put(table, deleted);
            }
        });
        return out;
    }
}
