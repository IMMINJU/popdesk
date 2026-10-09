package io.popdesk.attachment;

import static io.popdesk.attachment.AttachmentRows.quote;

import io.popdesk.support.DbInspector;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Puts a failure into the app's database writes with a trigger whose name starts with
 * {@code test_} (tasks.md section 6): the issue number, step ③ of an upload, removing a file from
 * the {@code db} storage. Only rows that hold the test's own marker value fail, so other tests in
 * the same context are not touched. {@link #close()} drops every trigger this object made; use it
 * in try-with-resources.
 *
 * <p>A row matches when one of its columns, read as text, equals the marker, in the old or the new
 * row. With a state, the new row must also hold that state ({@code stored}). The trigger either
 * raises an error or, for a race, sleeps 3 seconds while it holds the row lock (under the
 * connection's 5-second {@code lock_timeout}).
 */
final class FailureTriggers implements AutoCloseable {

    private static final String FUNCTION = "test_popdesk_attachment_failure";

    private final DbInspector db;
    private final List<String> dropStatements = new ArrayList<>();

    FailureTriggers(DbInspector db) {
        this.db = db;
        db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                + "declare old_row jsonb; new_row jsonb;\n"
                + "begin\n"
                + "  if TG_OP <> 'INSERT' then old_row := to_jsonb(OLD); end if;\n"
                + "  if TG_OP <> 'DELETE' then new_row := to_jsonb(NEW); end if;\n"
                + "  if (exists (select 1 from jsonb_each_text(old_row) e where e.value = TG_ARGV[0])\n"
                + "      or exists (select 1 from jsonb_each_text(new_row) e where e.value = TG_ARGV[0]))\n"
                + "     and (TG_ARGV[1] = ''\n"
                + "      or exists (select 1 from jsonb_each_text(new_row) e where lower(e.value) = TG_ARGV[1])) then\n"
                + "    if TG_ARGV[2] = 'sleep' then\n"
                + "      perform pg_sleep(3);\n"
                + "    else\n"
                + "      raise exception 'test_ trigger: injected failure';\n"
                + "    end if;\n"
                + "  end if;\n"
                + "  if TG_OP = 'DELETE' then return OLD; end if;\n"
                + "  return NEW;\n"
                + "end $f$");
    }

    /** Fails the UPDATE that takes the issue number from the company row holding {@code companyCode}. */
    void failNumberOf(String companyTable, String companyCode) {
        add(companyTable, "update", companyCode, "", "fail");
    }

    /** Fails step ③ (the UPDATE to {@code stored}) of the attachment row holding {@code fileName}. */
    void failStoring(String attachmentTable, String fileName) {
        add(attachmentTable, "update", fileName, "stored", "fail");
    }

    /** Makes step ③ of the attachment row holding {@code fileName} wait 3 seconds while it holds the row. */
    void delayStoring(String attachmentTable, String fileName) {
        add(attachmentTable, "update", fileName, "stored", "sleep");
    }

    /** Fails step ② for the file {@code content}: the INSERT or UPDATE that puts it into attachment_content. */
    void failWritingContent(byte[] content) {
        add(AttachmentRows.CONTENT_TABLE, "insert or update", hexText(content), "", "fail");
    }

    /** Fails removing (DELETE or UPDATE) the row of {@code attachment_content} that holds {@code content}. */
    void failRemovingContent(byte[] content) {
        add(AttachmentRows.CONTENT_TABLE, "update or delete", hexText(content), "", "fail");
    }

    /** How a {@code bytea} value reads as text in {@code to_jsonb}: {@code \x} and lower-case hex. */
    private static String hexText(byte[] content) {
        return "\\x" + HexFormat.of().formatHex(content);
    }

    private void add(String table, String events, String marker, String state, String action) {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        db.schemaFixture("create trigger " + name + " before " + events + " on " + quote(table)
                + " for each row execute function " + FUNCTION + "(" + literal(marker) + ", " + literal(state) + ", "
                + literal(action) + ")");
        dropStatements.add("drop trigger if exists " + name + " on " + quote(table));
    }

    /** Drops this object's triggers. The shared function stays; it does nothing without a trigger. */
    void dropAll() {
        for (String drop : dropStatements) {
            db.schemaFixture(drop);
        }
        dropStatements.clear();
    }

    @Override
    public void close() {
        dropAll();
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
