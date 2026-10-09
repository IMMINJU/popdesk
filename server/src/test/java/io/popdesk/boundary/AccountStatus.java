package io.popdesk.boundary;

import io.popdesk.support.DbInspector;
import io.popdesk.support.DbInspector.Column;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suspends an account with SQL. Suspending has no API before a later slice, and changing an
 * account's status out of {@code ACTIVE} is one of the two changes to business rows that tests may
 * make with SQL (tasks.md 6절).
 *
 * <p>The account table is not named by the slice. It is found by its shape: the one table with a
 * text column holding the account's email, whose single-column primary key holds the account ID,
 * and with a column whose value in that row is {@code ACTIVE}. The new value is {@code SUSPENDED},
 * the third account status of the contract ({@code INVITED}, {@code ACTIVE}, {@code SUSPENDED}).
 *
 * <p>A copy of the notify package's class of the same name (report: candidate for support).
 */
final class AccountStatus {

    private static final Set<String> TEXT_TYPES = Set.of("text", "character varying", "USER-DEFINED");

    private static final Pattern PRIMARY_KEY = Pattern.compile("PRIMARY KEY \\(([^,)]+)\\)");

    private final DbInspector db;

    AccountStatus(DbInspector db) {
        this.db = db;
    }

    /** Sets the status of account {@code accountId}, whose email is {@code email}, from ACTIVE to SUSPENDED. */
    void suspend(long accountId, String email) {
        List<String> found = new ArrayList<>();
        for (String table : db.tables()) {
            String key = primaryKey(table);
            if (key == null) {
                continue;
            }
            String row = quote(key) + "::text = '" + accountId + "'";
            List<Column> columns = db.columns(table);
            boolean holdsEmail = columns.stream().filter(c -> TEXT_TYPES.contains(c.dataType())).anyMatch(c -> db.count(
                    "select count(*) from " + quote(table) + " where " + row + " and lower(" + quote(c.name())
                            + "::text) = lower(?)", email) == 1);
            if (!holdsEmail) {
                continue;
            }
            for (Column c : columns) {
                if (TEXT_TYPES.contains(c.dataType()) && db.count("select count(*) from " + quote(table) + " where "
                        + row + " and " + quote(c.name()) + "::text = 'ACTIVE'") == 1) {
                    found.add(table + "." + c.name());
                }
            }
        }
        if (found.size() != 1) {
            throw new AssertionError("Expected one account status column with ACTIVE for account " + accountId
                    + " but found " + found);
        }
        String[] tableAndColumn = found.getFirst().split("\\.", 2);
        String table = tableAndColumn[0];
        try {
            db.schemaFixture("update " + quote(table) + " set " + quote(tableAndColumn[1]) + " = 'SUSPENDED' where "
                    + quote(primaryKey(table)) + "::text = '" + accountId + "'");
        } catch (IllegalStateException e) {
            throw new AssertionError("Could not set " + found.getFirst() + " to SUSPENDED for account " + accountId, e);
        }
    }

    /** The single primary key column of a table, or null. */
    private String primaryKey(String table) {
        for (Map.Entry<String, String> c : db.constraints(table).entrySet()) {
            Matcher m = PRIMARY_KEY.matcher(c.getValue());
            if (m.matches()) {
                return m.group(1).strip().replace("\"", "");
            }
        }
        return null;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
