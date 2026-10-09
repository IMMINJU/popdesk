package io.popdesk.notification;

import java.time.Duration;
import java.time.Instant;

/**
 * A cleanup job that notification runs on a schedule and records (S0-AC-82, OPS-2). Any module
 * declares one as a bean. Every few seconds the scheduler compares the app clock with the job's last
 * run in {@link CleanupRecords}; when {@link #interval()} has passed (or the job never ran), it runs
 * the job and then records the time and the count. The scheduler pauses while this instance does not
 * hold the single-instance lock.
 */
public interface CleanupJob {

    /** The job's name in the record and in the metrics label {@code job}, for example {@code session-cleanup}. */
    String name();

    /** How much app time passes between two runs. */
    Duration interval();

    /**
     * Deletes what is due and returns how many it deleted.
     *
     * @param now the app clock's time of this run
     */
    long run(Instant now);
}
