package io.popdesk.notification;

import java.time.Instant;
import java.util.Optional;

/**
 * The record of cleanup jobs (S0-AC-82, OPS-2): one row per job, rewritten each time it runs, with when
 * it last ran and how many rows it deleted. The metrics {@code popdesk_cleanup_last_run_seconds} and
 * {@code popdesk_cleanup_deleted} come from it. {@link CleanupJob} beans are recorded by the scheduler;
 * a job that runs on its own calls {@link #record} after it has deleted.
 */
public interface CleanupRecords {

    /** Writes the job's row: it ran at {@code ranAt} and deleted {@code deleted} rows. */
    void record(String job, Instant ranAt, long deleted);

    /** When the job last ran, or empty when it never ran. */
    Optional<Instant> lastRun(String job);
}
