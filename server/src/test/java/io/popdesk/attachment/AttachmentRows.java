package io.popdesk.attachment;

import io.popdesk.support.DbInspector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads attachment rows for what HTTP cannot show: the file state ({@code pending}, {@code stored},
 * {@code purging}, {@code purged}), the storage kind, and the file content in
 * {@code attachment_content}.
 *
 * <p>The criteria fix only the content table's name. The metadata table is found by the data the
 * test made through the API: the one table (other than {@code attachment_content}) with a row whose
 * column, read as text, equals the attachment ID, and which has a column holding one of the four
 * states. States are read case-insensitively from a text or enum column.
 */
final class AttachmentRows {

    static final String CONTENT_TABLE = "attachment_content";

    static final Set<String> STATES = Set.of("pending", "stored", "purging", "purged");

    /** One row and the table it is in. */
    record Row(String table, Map<String, Object> values) {

        /** The state column's value, lower case. Fails with an AssertionError when the row has none. */
        String state() {
            for (Object v : values.values()) {
                if (v != null && STATES.contains(v.toString().toLowerCase(Locale.ROOT))) {
                    return v.toString().toLowerCase(Locale.ROOT);
                }
            }
            throw new AssertionError("No column of " + table + " holds a file state " + STATES + ": " + values);
        }

        /** The attachment ID: column {@code id}, else the row's first {@code uuid} value. Fails with an AssertionError when there is none. */
        String id() {
            if (values.get("id") instanceof UUID id) {
                return id.toString();
            }
            return values.values().stream().filter(v -> v instanceof UUID).map(Object::toString).findFirst()
                    .orElseThrow(() -> new AssertionError("No uuid column in " + table + ": " + values));
        }

        /** Whether a column holds {@code value}, ignoring case. */
        boolean holds(String value) {
            return values.values().stream().anyMatch(v -> v != null && v.toString().equalsIgnoreCase(value));
        }
    }

    private final DbInspector db;

    AttachmentRows(DbInspector db) {
        this.db = db;
    }

    /** The metadata row of an attachment, by its ID. */
    Row byId(String id) {
        List<Row> found = find(id, Set.of("uuid", "text", "character varying"));
        if (found.size() != 1) {
            throw new AssertionError("Expected one attachment row with ID " + id + " and a file state, found: " + found);
        }
        return found.getFirst();
    }

    /** The metadata row of an attachment, or empty when there is none (a row that was removed). */
    Optional<Row> findById(String id) {
        List<Row> found = find(id, Set.of("uuid", "text", "character varying"));
        if (found.size() > 1) {
            throw new AssertionError("More than one attachment row with ID " + id + ": " + found);
        }
        return found.stream().findFirst();
    }

    /** The one table (other than attachment_content) with a text column equal to {@code value}. */
    String tableHolding(String value) {
        List<String> tables = new ArrayList<>();
        for (String table : db.tables()) {
            for (DbInspector.Column column : db.columns(table)) {
                if (Set.of("text", "character varying").contains(column.dataType())
                        && db.count("select count(*) from " + quote(table) + " where " + quote(column.name())
                                + " = ?", value) > 0
                        && !tables.contains(table)) {
                    tables.add(table);
                }
            }
        }
        if (tables.size() != 1) {
            throw new AssertionError("Expected one table holding '" + value + "' but found " + tables);
        }
        return tables.getFirst();
    }

    /** Metadata rows whose text columns hold {@code fileName}. Empty when there is none. */
    List<Row> byFileName(String fileName) {
        return find(fileName, Set.of("text", "character varying"));
    }

    /** The {@code bytea} column of {@code attachment_content}. Fails with an AssertionError when there is none. */
    String contentColumn() {
        List<String> columns = db.columns(CONTENT_TABLE).stream()
                .filter(c -> c.dataType().equals("bytea"))
                .map(DbInspector.Column::name)
                .toList();
        if (columns.size() != 1) {
            throw new AssertionError("Expected one bytea column in " + CONTENT_TABLE + " but found " + columns
                    + "; tables: " + db.tables());
        }
        return columns.getFirst();
    }

    /** How many rows of {@code attachment_content} hold exactly these bytes. */
    long contentCopies(byte[] content) {
        return db.count("select count(*) from " + CONTENT_TABLE + " where " + quote(contentColumn()) + " = ?",
                (Object) content);
    }

    /** {@code bytea} columns of a table. */
    List<String> byteaColumns(String table) {
        return db.columns(table).stream()
                .filter(c -> c.dataType().equals("bytea"))
                .map(DbInspector.Column::name)
                .toList();
    }

    private List<Row> find(String value, Set<String> types) {
        List<Row> out = new ArrayList<>();
        for (String table : db.tables()) {
            if (table.equals(CONTENT_TABLE)) {
                continue;
            }
            for (DbInspector.Column column : db.columns(table)) {
                if (!types.contains(column.dataType())) {
                    continue;
                }
                for (Map<String, Object> values : db.rows("select * from " + quote(table) + " where "
                        + quote(column.name()) + "::text = ?", value)) {
                    Row row = new Row(table, values);
                    if (hasState(row)) {
                        out.add(row);
                    }
                }
            }
        }
        return out;
    }

    private static boolean hasState(Row row) {
        return row.values().values().stream()
                .anyMatch(v -> v != null && STATES.contains(v.toString().toLowerCase(Locale.ROOT)));
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
