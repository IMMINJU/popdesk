package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AttachmentStorage} in the table {@code attachment_content} ({@code POPDESK_STORAGE=db}): one
 * row per attachment, written with {@code setBinaryStream} and read in pieces with {@code substring}, so
 * the driver never holds a whole file (architecture.md "Attachment storage"). Each call is its own
 * statement outside any transaction: writing commits before the business transaction begins
 * (S0-AC-56), and a download does not hold a connection between pieces.
 */
final class DatabaseAttachmentStorage implements AttachmentStorage {

    static final String KIND = "db";
    static final String TABLE = "attachment_content";

    private final JdbcTemplate jdbc;

    DatabaseAttachmentStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String location() {
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
        byte[] piece = jdbc.query("select substring(data from ? for ?) from attachment_content where attachment_id = ?",
                rs -> rs.next() ? rs.getBytes(1) : null, Math.toIntExact(offset + 1), length, attachmentId);
        if (piece == null) {
            throw new StorageFileMissingException("The db storage has no contents for the attachment");
        }
        return piece;
    }

    @Override
    public void delete(UUID attachmentId) {
        jdbc.update("delete from attachment_content where attachment_id = ?", attachmentId);
    }

    @Override
    public OptionalLong size(UUID attachmentId) {
        Long size = jdbc.query("select octet_length(data) from attachment_content where attachment_id = ?",
                rs -> rs.next() ? (Long) rs.getLong(1) : null, attachmentId);
        return size == null ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /** The table comes with the migrations: there is no marker to lose. */
    @Override
    public boolean hasMarker() {
        return true;
    }

    @Override
    public void writeMarker() {
        // Nothing to leave: see hasMarker.
    }

    @Override
    public void checkWritable() {
        // The start already wrote to this database when it took the instance lock and migrated.
    }
}
