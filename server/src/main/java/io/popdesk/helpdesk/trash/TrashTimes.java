package io.popdesk.helpdesk.trash;

import java.time.Duration;
import java.time.Instant;

/**
 * When popdesk purges an issue in the trash by itself (TRASH-3, S7A-AC-31): 30 days after it was
 * deleted, counted on the instant line, so a time zone and its daylight saving change nothing. No I/O.
 */
public final class TrashTimes {

    /** How long an issue stays in the trash. */
    public static final Duration KEPT = Duration.ofDays(30);

    private TrashTimes() {}

    /**
     * When the issue is due to be purged.
     *
     * @param deletedAt when it was deleted into the trash, not null, any instant
     * @return {@code deletedAt} plus exactly 30 times 24 hours
     */
    public static Instant purgeAt(Instant deletedAt) {
        return deletedAt.plus(KEPT);
    }

    /**
     * Whether the issue is due to be purged at {@code now}: 30 days or more after it was deleted. At the
     * very instant of {@link #purgeAt} it is due; a microsecond before, it is not.
     *
     * @param deletedAt when it was deleted into the trash, not null
     * @param now the time of the check, not null; before {@code deletedAt} it is never due
     * @return true when {@code now} is at or after {@link #purgeAt}
     */
    public static boolean due(Instant deletedAt, Instant now) {
        return !now.isBefore(purgeAt(deletedAt));
    }

    /**
     * The latest delete time an issue may have to be due at {@code now}: what a query compares
     * {@code deleted_at} with ({@code deleted_at <= deletedUntil(now)}), the same line as {@link #due}.
     *
     * @param now the time of the check, not null
     * @return {@code now} minus 30 days
     */
    public static Instant deletedUntil(Instant now) {
        return now.minus(KEPT);
    }
}
