package io.popdesk.issue;

import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes a customer out of a company with SQL. Removing a membership has no API before slice 1
 * (PROJ-4), and it is one of the two changes to business rows that tests may make with SQL
 * (tasks.md 6절). A customer who leaves still sees the issues they filed (permissions.md rule 2),
 * so the issue list test uses it to check that they still list them.
 *
 * <p>A copy of {@code io.popdesk.boundary.Membership} (report: candidate for support).
 *
 * <p>The membership table is not named by the slice. It is found by its shape: a table with a
 * foreign key to the account table and one to the company table, and no columns but those keys,
 * {@code tenant_id}, its primary key, and times. The issue table also points at both, but has text
 * columns, so it does not match.
 */
final class Membership {

    private static final Set<String> TIME_TYPES = Set.of("timestamp with time zone", "timestamp without time zone",
            "date");

    /** A single-column foreign key. */
    private record ForeignKey(String column, String targetTable, String targetColumn) {}

    private final DbInspector db;

    Membership(DbInspector db) {
        this.db = db;
    }

    /** Deletes the row that makes {@code accountId} a member of the company {@code companyCode}. */
    void remove(long accountId, String companyCode) {
        List<String> done = new ArrayList<>();
        for (String table : db.tables()) {
            List<ForeignKey> keys = foreignKeys(table);
            if (keys.size() < 2 || !onlyKeysAndTimes(table, keys)) {
                continue;
            }
            for (ForeignKey account : keys) {
                for (ForeignKey company : keys) {
                    if (account == company) {
                        continue;
                    }
                    String companyKey = keyOfRowHolding(company, companyCode);
                    if (companyKey == null || !isAccountKey(account)) {
                        continue;
                    }
                    String where = " where " + quote(account.column()) + "::text = '" + accountId + "' and "
                            + quote(company.column()) + "::text = '" + companyKey.replace("'", "''") + "'";
                    long n = db.count("select count(*) from " + quote(table) + where);
                    if (n > 0) {
                        db.schemaFixture("delete from " + quote(table) + where);
                        done.add(table + " (" + n + ")");
                    }
                }
            }
        }
        if (done.size() != 1) {
            throw new AssertionError("Expected one membership table with a row for account " + accountId
                    + " in " + companyCode + " but deleted from " + done);
        }
    }

    private boolean onlyKeysAndTimes(String table, List<ForeignKey> keys) {
        Set<String> keyColumns = new HashSet<>();
        keys.forEach(k -> keyColumns.add(k.column()));
        for (String definition : db.constraints(table).values()) {
            if (definition.startsWith("PRIMARY KEY (")) {
                for (String c : definition.substring(13, definition.length() - 1).split(",")) {
                    keyColumns.add(c.strip().replace("\"", ""));
                }
            }
        }
        for (Column c : db.columns(table)) {
            if (!keyColumns.contains(c.name()) && !c.name().equals("tenant_id") && !TIME_TYPES.contains(c.dataType())) {
                return false;
            }
        }
        return true;
    }

    /** The target column value of the row in the key's target table that has a text column equal to {@code value}. */
    private String keyOfRowHolding(ForeignKey key, String value) {
        for (Column c : db.columns(key.targetTable())) {
            if (c.dataType().equals("text") || c.dataType().equals("character varying")) {
                List<Map<String, Object>> found = db.rows("select " + quote(key.targetColumn()) + "::text as k from "
                        + quote(key.targetTable()) + " where " + quote(c.name()) + " = ?", value);
                if (found.size() == 1) {
                    return String.valueOf(found.getFirst().get("k"));
                }
            }
        }
        return null;
    }

    /** Whether the key's target table holds accounts: it has a column holding an email address. */
    private boolean isAccountKey(ForeignKey key) {
        for (Column c : db.columns(key.targetTable())) {
            if ((c.dataType().equals("text") || c.dataType().equals("character varying")) && db.count(
                    "select count(*) from " + quote(key.targetTable()) + " where " + quote(c.name())
                            + " like '%@%.%'") > 0) {
                return true;
            }
        }
        return false;
    }

    private List<ForeignKey> foreignKeys(String table) {
        return db.rows("select a.attname as col, ft.relname as target, fa.attname as target_col from pg_constraint c "
                + "join pg_class t on t.oid = c.conrelid join pg_namespace s on s.oid = t.relnamespace "
                + "join pg_class ft on ft.oid = c.confrelid "
                + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1] "
                + "join pg_attribute fa on fa.attrelid = c.confrelid and fa.attnum = c.confkey[1] "
                + "where c.contype = 'f' and cardinality(c.conkey) = 1 and s.nspname = ? and t.relname = ?",
                db.schema(), table).stream()
                .map(r -> new ForeignKey((String) r.get("col"), (String) r.get("target"), (String) r.get("target_col")))
                .toList();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
