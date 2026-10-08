package io.popdesk.issue;

import io.popdesk.support.DbInspector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the company, issue, and attachment tables by the data the test filed through the API, not
 * by name: the slice does not fix table or column names. A table "holds" a value when one of its
 * text columns equals it exactly.
 *
 * <ul>
 *   <li>company table: holds the company's legal name, and the issue table has a foreign key to it;
 *   <li>issue table: holds the issue's title;
 *   <li>attachment table: holds the file name, and has a foreign key to the issue table.
 * </ul>
 *
 * Searches every schema but the system ones, so it works whatever schema the migrations use.
 */
final class IssueTables {

    /** A table with its schema. */
    record Table(String schema, String name) {

        /** Quoted, for SQL. */
        String sql() {
            return quote(schema) + "." + quote(name);
        }

        @Override
        public String toString() {
            return schema + "." + name;
        }
    }

    /** A column of a table that holds a given value. */
    record Holder(Table table, String column) {}

    /** A constraint of a table: kind ({@code u}, {@code f}, ...), sorted columns, what a foreign key points at. */
    record Constraint(String name, String type, Set<String> columns, Table target, boolean deferrable,
            boolean initiallyDeferred) {}

    /** A unique index of a table: sorted columns and its definition. */
    record UniqueIndex(String name, Set<String> columns, String definition) {}

    private static final String USER_COLUMNS = "from information_schema.columns c "
            + "join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name "
            + "where t.table_type = 'BASE TABLE' "
            + "and c.table_schema not in ('pg_catalog', 'information_schema') and c.table_schema not like 'pg_toast%' ";

    private final DbInspector db;

    IssueTables(DbInspector db) {
        this.db = db;
    }

    /** Every text column, in every user table, whose value equals {@code value} in some row. */
    List<Holder> holders(String value) {
        List<Holder> out = new ArrayList<>();
        for (Map<String, Object> col : db.rows("select c.table_schema, c.table_name, c.column_name " + USER_COLUMNS
                + "and c.data_type in ('text', 'character varying', 'character') order by 1, 2, 3")) {
            Table table = new Table((String) col.get("table_schema"), (String) col.get("table_name"));
            String column = (String) col.get("column_name");
            if (db.count("select count(*) from " + table.sql() + " where " + quote(column) + " = ?", value) > 0) {
                out.add(new Holder(table, column));
            }
        }
        return out;
    }

    /** The table holding {@code issueTitle} whose foreign key points at a table holding {@code legalName}. */
    Found find(String legalName, String issueTitle) {
        List<Holder> companies = holders(legalName);
        List<Holder> issues = holders(issueTitle);
        // One entry per pair of tables: a table may hold the value in more than one column.
        Map<List<Object>, Found> found = new LinkedHashMap<>();
        for (Holder issue : issues) {
            for (Holder company : companies) {
                for (Constraint c : constraints(issue.table())) {
                    if (c.type().equals("f") && company.table().equals(c.target())) {
                        found.putIfAbsent(List.of(company.table(), issue.table(), c.columns()),
                                new Found(company, issue, c.columns()));
                    }
                }
            }
        }
        if (found.size() != 1) {
            throw new AssertionError("Expected one issue table with a foreign key to one company table; tables "
                    + "holding the legal name: " + companies + ", the title: " + issues + ", pairs: " + found.values());
        }
        return found.values().iterator().next();
    }

    /** The company table, the issue table, and the issue table's foreign key columns to the company. */
    record Found(Holder company, Holder issue, Set<String> companyKey) {}

    /** The table holding {@code fileName} with a foreign key to {@code issueTable}, and that key. */
    Constraint attachmentKeyToIssue(String fileName, Table issueTable) {
        Map<List<Object>, Constraint> keys = new LinkedHashMap<>();
        List<Holder> holders = holders(fileName);
        for (Holder h : holders) {
            for (Constraint c : constraints(h.table())) {
                if (c.type().equals("f") && issueTable.equals(c.target())) {
                    keys.putIfAbsent(List.of(h.table(), c.name()), c);
                }
            }
        }
        if (keys.size() != 1) {
            throw new AssertionError("Expected one foreign key to " + issueTable + " from a table holding the file "
                    + "name; tables holding it: " + holders + ", keys: " + keys.values());
        }
        return keys.values().iterator().next();
    }

    /** Integer columns of a table (smallint, integer, bigint). */
    List<String> integerColumns(Table table) {
        return db.rows("select c.column_name " + USER_COLUMNS + "and c.table_schema = ? and c.table_name = ? "
                + "and c.data_type in ('smallint', 'integer', 'bigint') order by c.ordinal_position",
                table.schema(), table.name())
                .stream().map(r -> (String) r.get("column_name")).toList();
    }

    /** The one row where {@code column} equals {@code value}. */
    Map<String, Object> row(Holder holder, String value) {
        return db.row("select * from " + holder.table().sql() + " where " + quote(holder.column()) + " = ?", value);
    }

    /** {@code xmin} of the row: it changes whenever the row is updated, and not when it is only locked. */
    String rowVersion(Holder holder, String value) {
        return (String) db.row("select xmin::text as x from " + holder.table().sql() + " where "
                + quote(holder.column()) + " = ?", value).get("x");
    }

    boolean notNull(Table table, String column) {
        return Boolean.TRUE.equals(db.row("select a.attnotnull from pg_attribute a where a.attrelid = "
                + regclass() + " and a.attname = ?", table.schema(), table.name(), column).get("attnotnull"));
    }

    List<Constraint> constraints(Table table) {
        List<Constraint> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("select c.conname, c.contype::text as contype, c.condeferrable, "
                + "c.condeferred, fn.nspname as fschema, ft.relname as ftable, array_to_string(array("
                + "select a.attname from unnest(c.conkey) k join pg_attribute a on a.attrelid = c.conrelid "
                + "and a.attnum = k order by a.attname), ',') as cols "
                + "from pg_constraint c left join pg_class ft on ft.oid = c.confrelid "
                + "left join pg_namespace fn on fn.oid = ft.relnamespace "
                + "where c.conrelid = " + regclass() + " order by c.conname", table.schema(), table.name())) {
            Table target = r.get("ftable") == null ? null
                    : new Table((String) r.get("fschema"), (String) r.get("ftable"));
            out.add(new Constraint((String) r.get("conname"), (String) r.get("contype"), split((String) r.get("cols")),
                    target, Boolean.TRUE.equals(r.get("condeferrable")), Boolean.TRUE.equals(r.get("condeferred"))));
        }
        return out;
    }

    List<UniqueIndex> uniqueIndexes(Table table) {
        List<UniqueIndex> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("select ic.relname as name, pg_get_indexdef(i.indexrelid) as def, "
                + "array_to_string(array(select a.attname from unnest(i.indkey) k join pg_attribute a "
                + "on a.attrelid = i.indrelid and a.attnum = k order by a.attname), ',') as cols "
                + "from pg_index i join pg_class ic on ic.oid = i.indexrelid "
                + "where i.indisunique and i.indrelid = " + regclass() + " order by 1", table.schema(), table.name())) {
            out.add(new UniqueIndex((String) r.get("name"), split((String) r.get("cols")), (String) r.get("def")));
        }
        return out;
    }

    private static String regclass() {
        return "(quote_ident(?) || '.' || quote_ident(?))::regclass";
    }

    private static Set<String> split(String cols) {
        Set<String> out = new TreeSet<>();
        if (cols != null && !cols.isEmpty()) {
            out.addAll(List.of(cols.split(",")));
        }
        return out;
    }

    static Set<String> union(Set<String> a, String b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.add(b);
        return new TreeSet<>(out);
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
