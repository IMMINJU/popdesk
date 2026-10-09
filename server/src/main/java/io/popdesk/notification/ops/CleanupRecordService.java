package io.popdesk.notification.ops;

import io.popdesk.notification.CleanupRecords;
import io.popdesk.notification.ops.OpsRepository.CleanupRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link CleanupRecords} on the table {@code cleanup_run}. */
@Service
class CleanupRecordService implements CleanupRecords {

    private final OpsRepository ops;

    CleanupRecordService(OpsRepository ops) {
        this.ops = ops;
    }

    @Override
    @Transactional
    public void record(String job, Instant ranAt, long deleted) {
        ops.upsertCleanup(job, ranAt.truncatedTo(ChronoUnit.MICROS), deleted);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastRun(String job) {
        return ops.findCleanup(job).map(CleanupRow::lastRunAt);
    }
}
