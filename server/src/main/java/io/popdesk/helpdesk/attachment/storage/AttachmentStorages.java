package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where attachment file contents live (architecture.md "Attachment storage"), and the one place that
 * picks a storage. A new file goes to the storage the settings name ({@code POPDESK_STORAGE}). A file
 * that is there already is read and removed where its row says it is, by the row's {@code storage} and
 * {@code storage_location}: changing the settings moves no file (S5B-AC-10, S5B-AC-11).
 *
 * <p>Reading is customer data without a scope, so only
 * {@code io.popdesk.helpdesk.attachment.AttachmentService} calls {@link #read}, after it checked the
 * viewer's scope (S0-AC-84). The system jobs remove files, and nothing else.
 */
@Component
public class AttachmentStorages {

    private final JdbcTemplate jdbc;
    private final AttachmentStorage current;
    private final Map<String, AttachmentStorage> places = new ConcurrentHashMap<>();

    @Autowired
    AttachmentStorages(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        String chosen = environment.getProperty("POPDESK_STORAGE", DatabaseAttachmentStorage.KIND);
        if (!DatabaseAttachmentStorage.KIND.equals(chosen)) {
            // disk and s3 come in a later slice; storing in the database instead would surprise a backup.
            throw new IllegalStateException("POPDESK_STORAGE must be db in this version; disk and s3 are not "
                    + "available yet (docs/operate/configuration.md).");
        }
        this.current = new DatabaseAttachmentStorage(jdbc);
    }

    /** The storage kind of new files, recorded on their rows: {@code db}, {@code disk}, or {@code s3}. */
    public String kind() {
        return current.kind();
    }

    /** Where new files go within their storage kind, recorded on their rows (S0-AC-56). */
    public String location() {
        return current.location();
    }

    /** Writes the contents of a new attachment to the storage of new files, streamed. */
    public void write(UUID attachmentId, InputStream contents, long size) {
        current.write(attachmentId, contents, size);
    }

    /**
     * Up to {@code length} bytes of the contents from {@code offset} (0-based), from the place the
     * attachment's row names; fewer at the end. Contents that are not there are a
     * {@link StorageFileMissingException}, never an empty answer (S5B-AC-31).
     */
    public byte[] read(String kind, String location, UUID attachmentId, long offset, int length) {
        return place(kind, location).read(attachmentId, offset, length);
    }

    /** Removes the contents from the place the attachment's row names; nothing happens when there are none. */
    public void delete(String kind, String location, UUID attachmentId) {
        place(kind, location).delete(attachmentId);
    }

    /** The storage of one place rows point to, made when it is first asked for. */
    private AttachmentStorage place(String kind, String location) {
        if (kind == null || location == null) {
            throw new StorageException("An attachment row names no storage");
        }
        if (current.kind().equals(kind) && current.location().equals(location)) {
            return current;
        }
        return places.computeIfAbsent(kind + " " + location, key -> open(kind, location));
    }

    private AttachmentStorage open(String kind, String location) {
        if (DatabaseAttachmentStorage.KIND.equals(kind)) {
            return new DatabaseAttachmentStorage(jdbc);
        }
        throw new StorageException("An attachment row names a storage kind this version does not know");
    }
}
