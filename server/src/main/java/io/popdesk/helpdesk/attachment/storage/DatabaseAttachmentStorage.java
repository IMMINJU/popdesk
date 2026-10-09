package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link AttachmentStorage} in the table {@code attachment_content} ({@code POPDESK_STORAGE=db}): one
 * row per attachment, written with {@code setBinaryStream} and read in pieces with {@code substring}, so
 * the driver never holds a whole file (architecture.md "Attachment storage"). Each call is its own
 * statement outside any transaction: writing commits before the business transaction begins
 * (S0-AC-56), and a download does not hold a connection between pieces.
 */
@Component
class DatabaseAttachmentStorage implements AttachmentStorage {

    static final String KIND = "db";
    static final String TABLE = "attachment_content";

    private final JdbcTemplate jdbc;

    DatabaseAttachmentStorage(JdbcTemplate jdbc, Environment environment) {
        String chosen = environment.getProperty("POPDESK_STORAGE", KIND);
        if (!KIND.equals(chosen)) {
            // disk and s3 come in a later slice; storing in the database instead would surprise a backup.
            throw new IllegalStateException("POPDESK_STORAGE must be db in this version; disk and s3 are not "
                    + "available yet (docs/operate/configuration.md).");
        }
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String location(UUID attachmentId) {
        return TABLE;
    }

    @Override
    public void write(UUID attachmentId, InputStream contents, long size) {
        jdbc.update("insert into attachment_content (attachment_id, tenant_id, data) values (?, 1, ?)", ps -> {
            ps.setObject(1, attachmentId);
            ps.setBinaryStream(2, contents, size);
        });
    }

    @Override
    public byte[] read(UUID attachmentId, long offset, int length) {
        return jdbc.query("select substring(data from ? for ?) from attachment_content where attachment_id = ?",
                rs -> rs.next() ? rs.getBytes(1) : new byte[0], Math.toIntExact(offset + 1), length, attachmentId);
    }

    @Override
    public void delete(UUID attachmentId) {
        jdbc.update("delete from attachment_content where attachment_id = ?", attachmentId);
    }
}
