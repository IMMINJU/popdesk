package io.popdesk.trash;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

/**
 * The two cleanup jobs of slice 7a as the system status shows them (C-18, {@code GET /api/console/system/status},
 * {@code cleanupJobs}): {@code trash-purge} every hour (S7A-AC-31) and {@code issue-auto-close} every 10 minutes
 * (S7A-AC-40). Their names are in the contract documents ({@code docs/operate/configuration.md}) and in S7A-AC-52.
 *
 * <p>Two new jobs come at once, so {@link io.popdesk.support.CleanupRecords#ofNewJob}, which finds "the" record that
 * names no earlier job, cannot tell them apart; their names can. The status line carries the same record (time and
 * count) the job leaves (OPS-2).
 *
 * <p>Public, so that the tests of other packages and tabs can use it.
 */
public final class TrashJobs {

    public static final String PURGE = "trash-purge";
    public static final String AUTO_CLOSE = "issue-auto-close";

    /** The scheduler wakes every few seconds of real time; allow several rounds. */
    public static final Duration RUN = Duration.ofSeconds(60);

    private TrashJobs() {}

    /**
     * The status line of {@code job} when its last run was at or after {@code since}, else null. {@code admin} must
     * be signed in at the app's time now.
     */
    public static JsonNode run(Session admin, String job, Instant since) {
        JsonNode jobs = admin.client().get("/api/console/system/status").expect(200).json().path("cleanupJobs");
        for (JsonNode line : jobs) {
            if (job.equals(line.path("name").asString(null)) && line.path("lastRunAt").isString()
                    && !lastRunAt(line).isBefore(since)) {
                return line;
            }
        }
        return null;
    }

    /** Waits for a run of {@code job} at or after {@code since} and gives its status line. */
    public static JsonNode awaitRun(Session admin, String job, Instant since) {
        return Wait.until(job + " in the system status with a run at or after " + since, RUN,
                () -> run(admin, job, since));
    }

    /** Waits for a run of {@code job} at or after {@code since} that counts at least {@code atLeast}. */
    public static JsonNode awaitRun(Session admin, String job, Instant since, long atLeast) {
        return Wait.until(job + " in the system status with a run at or after " + since + " that counts at least "
                + atLeast, RUN, () -> {
                    JsonNode line = run(admin, job, since);
                    return line != null && count(line) >= atLeast ? line : null;
                });
    }

    /** {@code lastRunAt} of a status line. */
    public static Instant lastRunAt(JsonNode line) {
        try {
            return OffsetDateTime.parse(line.path("lastRunAt").stringValue()).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new AssertionError("No time in lastRunAt of " + line, e);
        }
    }

    /** {@code deleted} of a status line: the rows the run purged or the issues it closed. */
    public static long count(JsonNode line) {
        JsonNode deleted = line.path("deleted");
        if (!deleted.isIntegralNumber()) {
            throw new AssertionError("No count in deleted of " + line);
        }
        return deleted.asLong();
    }
}
