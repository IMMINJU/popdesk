package io.popdesk.notification.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * Whether cleanup jobs are late (S7B-AC-10): a job is stalled when more than its interval plus
 * {@link #GRACE} has passed since its last success. A job that never succeeded is measured from the
 * round in which this instance first held the instance lock. Pure: no clock, no database.
 */
public final class StalledCleanup {

    /** What a job may be late on top of its interval before it counts as stalled (acceptance.md 5절 3). */
    public static final Duration GRACE = Duration.ofHours(24);

    /**
     * One cleanup job as the judgement sees it.
     *
     * @param name the job's name, for the reader only; the judgement does not look at it
     * @param interval how much app time passes between two runs; positive
     * @param lastSuccess when the job last succeeded, or null when it never did
     */
    public record Job(String name, Duration interval, Instant lastSuccess) {

        public Job {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(interval, "interval");
        }
    }

    private StalledCleanup() {}

    /**
     * Whether one job is stalled.
     *
     * @param lastSuccess the job's last success, or null when it never succeeded
     * @param firstHeld the time of the round in which this instance first held the lock; any instant, used only
     *     when {@code lastSuccess} is null
     * @param interval the job's interval; positive
     * @param now the time of this round; any instant, also one before {@code lastSuccess} (then not stalled)
     * @return true when {@code now} is strictly more than {@code interval} plus {@link #GRACE} after
     *     {@code lastSuccess} (or {@code firstHeld}); false at exactly that time and before it
     */
    public static boolean stalled(Instant lastSuccess, Instant firstHeld, Duration interval, Instant now) {
        Instant from = lastSuccess != null ? lastSuccess : Objects.requireNonNull(firstHeld, "firstHeld");
        return now.isAfter(from.plus(interval).plus(GRACE));
    }

    /**
     * Whether any of the jobs is stalled ({@link #stalled}); one alert covers them all (S7B-AC-11).
     *
     * @param jobs the cleanup jobs the app has now; a name that is only in the record of earlier runs is not among
     *     them and is never judged. Empty gives false
     * @param firstHeld as for {@link #stalled}
     * @param now as for {@link #stalled}
     * @return true when at least one job is stalled
     */
    public static boolean anyStalled(Collection<Job> jobs, Instant firstHeld, Instant now) {
        return jobs.stream().anyMatch(j -> stalled(j.lastSuccess(), firstHeld, j.interval(), now));
    }
}
