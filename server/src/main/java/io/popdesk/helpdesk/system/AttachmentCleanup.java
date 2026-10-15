package io.popdesk.helpdesk.system;

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
 * The daily attachment cleanup, {@code attachment-cleanup} (S0-AC-62, S0-AC-82). It finishes what
 * uploads and deletes left half done, from the rows only: it never lists the storage to find files the
 * database does not know.
 *
 * <ol>
 *   <li>A {@code pending} row over an hour old is an upload whose business transaction never came, a
 *       failed filing among them (S0-AC-63). It becomes {@code purging} with a conditional UPDATE, so
 *       when step ③ of the upload holds the row, the one that commits first wins and the other changes
 *       nothing.
 *   <li>Each {@code purging} row loses its file, in the storage its row names (S5B-AC-12; a file that is
 *       already gone counts as removed), and becomes {@code purged}; the row of a purged issue is deleted
 *       instead (S7A-AC-33).
 * </ol>
 *
 * The count recorded is the rows that became {@code purged} or were deleted. A file that cannot be removed is counted and
 * the run goes on with the next row, but a storage that cannot be reached at all ends the run there: every
 * other row would wait for the same time limit, and the other scheduled work waits behind this job
 * (S5B-AC-35). Either way the run fails and is tried again.
 */
@Component
class AttachmentCleanup implements CleanupJob {

    static final String NAME = "attachment-cleanup";

    /** How long an upload may stay pending before the cleanup ends it (ATT-11). */
    static final Duration PENDING_FOR = Duration.ofHours(1);

    /**
     * The attachment's issue is purged, as SQL on {@code attachment}: its row goes with the file, as
     * nothing of a purged issue stays (TRASH-3, S7A-AC-33).
     */
    private static final String PURGED_ISSUE = "select 1 from issue i where i.id = attachment.issue_id "
            + "and i.purged_at is not null";

    private record Purging(UUID id, String storage, String location) {}

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanup.class);

    private final JdbcClient jdbc;
    private final AttachmentStorages storage;

    AttachmentCleanup(JdbcClient jdbc, AttachmentStorages storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return Duration.ofDays(1);
    }

    @Override
    public long run(Instant now) {
        jdbc.sql("update attachment set state = 'purging', file_name = null, deleted_at = ? "
                        + "where state = 'pending' and created_at < ?")
                .params(Timestamp.from(now), Timestamp.from(now.minus(PENDING_FOR)))
                .update();
        List<Purging> purging = jdbc.sql("select id, storage, storage_location from attachment "
                        + "where state = 'purging' order by id")
                .query((rs, row) -> new Purging(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
        long purged = 0;
        int failed = 0;
        for (Purging row : purging) {
            try {
                storage.delete(row.storage(), row.location(), row.id());
                purged += jdbc.sql("update attachment set state = 'purged' where id = ? and state = 'purging' "
                                + "and not exists (" + PURGED_ISSUE + ")")
                        .params(row.id()).update();
                purged += jdbc.sql("delete from attachment where id = ? and state = 'purging' and exists ("
                                + PURGED_ISSUE + ")")
                        .params(row.id()).update();
            } catch (StorageUnreachableException e) {
                log.warn("The {} storage could not be reached; this run of the attachment cleanup ends here and "
                        + "the next run tries again.", row.storage());
                throw e;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Removing the file of an attachment failed ({}); the next run tries again.",
                        e.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            throw new IllegalStateException(failed + " attachment files could not be removed");
        }
        return purged;
    }
}
