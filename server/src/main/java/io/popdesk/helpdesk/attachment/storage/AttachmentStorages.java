package io.popdesk.helpdesk.attachment.storage;

import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Where attachment file contents live (architecture.md "Attachment storage"), and the one place that
 * picks a storage. A new file goes to the storage the settings name ({@code POPDESK_STORAGE}). A file
 * that is there already is read, removed, and looked at where its row says it is, by the row's
 * {@code storage} and {@code storage_location}: changing the settings moves no file (S5B-AC-10,
 * S5B-AC-11). The storage of a place rows point to is made when it is first asked for.
 *
 * <p>Reading is customer data without a scope, so only
 * {@code io.popdesk.helpdesk.attachment.AttachmentService} calls {@link #read}, after it checked the
 * viewer's scope (S0-AC-84). The system jobs remove files and the {@code popdesk} command looks whether
 * they are there, and nothing else.
 *
 * <p>Settings of the wrong shape are a {@link StorageException} from the constructor, so the app does
 * not start with them (S5B-AC-23). What the storages hold is checked by {@link StorageStartCheck}.
 */
@Component
public class AttachmentStorages implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final StorageSettings settings;
    private final AttachmentStorage current;
    private final Map<String, AttachmentStorage> places = new ConcurrentHashMap<>();
    private S3Access s3;

    @Autowired
    AttachmentStorages(JdbcTemplate jdbc, Environment environment) {
        this(jdbc, environment::getProperty);
    }

    /**
     * For the {@code popdesk} command, which has no Spring context: the storages by the settings
     * {@code settings} gives (null for a key that is not set), with the database storage on
     * {@code connection}. The connection stays the caller's to close.
     */
    public AttachmentStorages(Connection connection, UnaryOperator<String> settings) {
        this(new JdbcTemplate(new SingleConnectionDataSource(connection, true)), settings);
    }

    private AttachmentStorages(JdbcTemplate jdbc, UnaryOperator<String> lookup) {
        this.jdbc = jdbc;
        this.settings = StorageSettings.read(lookup);
        List<String> problems = settings.problems();
        if (!problems.isEmpty()) {
            throw new StorageException(String.join(" ", problems) + " popdesk does not start with these settings "
                    + "(docs/operate/configuration.md).");
        }
        this.current = switch (settings.kind()) {
            case DiskAttachmentStorage.KIND -> new DiskAttachmentStorage(settings.folderPath());
            case S3AttachmentStorage.KIND ->
                    new S3AttachmentStorage(s3(), new BucketLocation(settings.bucket(), StoragePaths.PREFIX));
            default -> new DatabaseAttachmentStorage(jdbc);
        };
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

    /**
     * How many bytes of contents the attachment has in the place its row names, or empty when it has
     * none there. Looks only: it reads no contents and changes nothing (S5B-AC-40, S5B-AC-43).
     */
    public OptionalLong checkSize(String kind, String location, UUID attachmentId) {
        return place(kind, location).size(attachmentId);
    }

    @Override
    public synchronized void close() {
        if (s3 != null) {
            s3.close();
        }
    }

    StorageSettings settings() {
        return settings;
    }

    /** The storage of new files. */
    AttachmentStorage current() {
        return current;
    }

    /** The storage of one place rows point to, made when it is first asked for. */
    AttachmentStorage place(String kind, String location) {
        if (kind == null || location == null) {
            throw new StorageException("An attachment row names no storage");
        }
        if (current.kind().equals(kind) && current.location().equals(location)) {
            return current;
        }
        return places.computeIfAbsent(kind + " " + location, key -> open(kind, location));
    }

    private AttachmentStorage open(String kind, String location) {
        switch (kind) {
            case DatabaseAttachmentStorage.KIND:
                return new DatabaseAttachmentStorage(jdbc);
            case DiskAttachmentStorage.KIND:
                try {
                    return new DiskAttachmentStorage(Path.of(location));
                } catch (InvalidPathException e) {
                    throw new StorageException("An attachment row names a folder that is no path on this system");
                }
            case S3AttachmentStorage.KIND:
                BucketLocation where = BucketLocation.parse(location).orElseThrow(
                        () -> new StorageException("An attachment row names an s3 location that is no bucket and "
                                + "prefix"));
                return new S3AttachmentStorage(s3(), where);
            default:
                throw new StorageException("An attachment row names a storage kind this version does not know");
        }
    }

    /** The S3 clients, made when S3 is first needed: an installation without S3 never makes one (S5B-AC-27). */
    private synchronized S3Access s3() {
        if (s3 == null) {
            List<String> missing = settings.missingS3Keys(false);
            if (!missing.isEmpty()) {
                throw new StorageException("Attachments are stored in s3 and these settings are missing: "
                        + String.join(", ", missing) + ".");
            }
            if (settings.endpoint() != null && settings.endpointAddress() == null) {
                throw new StorageException("S3_ENDPOINT must be an http or https address.");
            }
            s3 = new S3Access(settings);
        }
        return s3;
    }
}
