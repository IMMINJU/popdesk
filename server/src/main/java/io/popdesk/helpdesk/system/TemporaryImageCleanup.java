package io.popdesk.helpdesk.system;

import io.popdesk.Transactions;
import io.popdesk.helpdesk.attachment.AttachmentService;
import io.popdesk.helpdesk.attachment.storage.AttachmentStorages;
import io.popdesk.helpdesk.attachment.storage.StorageUnreachableException;
import io.popdesk.notification.CleanupJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The cleanup of temporary images, {@code temporary-image-cleanup}, every 10 minutes (ATT-7,
 * S5-AC-31). A temporary image that no save attached within 24 hours goes, its file and its row,
 * without a trace: nobody but its uploader ever saw it. With them go the rows of image uploads that
 * never finished, whatever state the daily attachment cleanup left them in.
 *
 * <p>Each image is its own transaction. The row is locked first, on the condition that it is still
 * not attached and expired, so a save that attaches the image at the same time either finished before
 * (the row no longer matches and stays, file and all) or waits and finds no image (S5-AC-32). Then
 * the file is removed, then the row. When removing the file fails the row stays, and the next run
 * tries again (S5-AC-34); an expired image is read by nobody in between. The file is removed from the
 * storage its row names (S5B-AC-12), and a storage that cannot be reached at all ends the run there, as
 * in the attachment cleanup (S5B-AC-35).
 */
@Component
class TemporaryImageCleanup implements CleanupJob {

    static final String NAME = "temporary-image-cleanup";

    private static final String EXPIRED = "inline and issue_id is null and created_at <= ?";

    private record Place(String storage, String location) {}

    private static final Logger log = LoggerFactory.getLogger(TemporaryImageCleanup.class);

    private final JdbcClient jdbc;
    private final AttachmentStorages storage;
    private final Transactions transactions;

    TemporaryImageCleanup(JdbcClient jdbc, AttachmentStorages storage, Transactions transactions) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(10);
    }

    @Override
    public long run(Instant now) {
        Timestamp until = Timestamp.from(now.minus(AttachmentService.IMAGE_LIFETIME));
        List<UUID> expired = jdbc.sql("select id from attachment where " + EXPIRED + " order by id")
                .params(until).query(UUID.class).list();
        long deleted = 0;
        int failed = 0;
        for (UUID id : expired) {
            try {
                deleted += transactions.run(() -> delete(id, until));
            } catch (StorageUnreachableException e) {
                log.warn("An attachment storage could not be reached; this run of the temporary image cleanup ends "
                        + "here and the next run tries again.");
                throw e;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Deleting an expired temporary image failed ({}); the next run tries again.",
                        e.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            log.warn("{} expired temporary images could not be deleted in this run.", failed);
        }
        return deleted;
    }

    /** The file, then the row, of one image if it is still expired and not attached; how many rows went. */
    private int delete(UUID id, Timestamp until) {
        Place locked = jdbc.sql("select storage, storage_location from attachment where id = ? and " + EXPIRED
                        + " for no key update")
                .params(id, until).query((rs, row) -> new Place(rs.getString(1), rs.getString(2))).optional()
                .orElse(null);
        if (locked == null) {
            return 0;
        }
        storage.delete(locked.storage(), locked.location(), id);
        return jdbc.sql("delete from attachment where id = ?").params(id).update();
    }
}
