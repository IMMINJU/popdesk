package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link AttachmentStorage} in the table {@code attachment_content} ({@code POPDESK_STORAGE=db}): one
 * row per attachment, written with {@code setBinaryStream} and read in pieces with {@code substring}, so
 * the driver never holds a whole file (architecture.md "Attachment storage").
 */
@Component
class DatabaseAttachmentStorage implements AttachmentStorage {

    private final JdbcTemplate jdbc;

    DatabaseAttachmentStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                rs -> rs.next() ? rs.getBytes(1) : new byte[0], offset + 1, length, attachmentId);
    }

    @Override
    public void delete(UUID attachmentId) {
        jdbc.update("delete from attachment_content where attachment_id = ?", attachmentId);
    }
}
