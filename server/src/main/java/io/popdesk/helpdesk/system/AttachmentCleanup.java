package io.popdesk.helpdesk.system;

import io.popdesk.helpdesk.attachment.storage.AttachmentStorage;
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
 *   <li>Each {@code purging} row loses its file (a file that is already gone counts as removed) and
 *       becomes {@code purged}.
 * </ol>
 *
 * The count recorded is the rows that became {@code purged}.
 */
@Component
class AttachmentCleanup implements CleanupJob {

    static final String NAME = "attachment-cleanup";

    /** How long an upload may stay pending before the cleanup ends it (ATT-11). */
    static final Duration PENDING_FOR = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanup.class);

    private final JdbcClient jdbc;
    private final AttachmentStorage storage;

    AttachmentCleanup(JdbcClient jdbc, AttachmentStorage storage) {
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
        List<UUID> purging = jdbc.sql("select id from attachment where state = 'purging' order by id")
                .query(UUID.class).list();
        long purged = 0;
        int failed = 0;
        for (UUID id : purging) {
            try {
                storage.delete(id);
                purged += jdbc.sql("update attachment set state = 'purged' where id = ? and state = 'purging'")
                        .params(id).update();
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
