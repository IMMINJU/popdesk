package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.fieldNames;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The cleanup jobs in the system status (OPS-2, OPS-3): per job its name, when it last ran, and how
 * many rows that run deleted. The names are those of the metrics label {@code job}; the test does not
 * fix the whole list, it runs the session cleanup as {@link CleanupRecordTest} does and reads its line.
 */
class CleanupJobsStatusTest extends BareAppTest {

    @Test
    @DisplayName("S4B-AC-30 after the session cleanup ran, the status lists it with the time it ran and the "
            + "sessions it ended; every job has a name, a time, and a count, in name order")
    void statusListsCleanupJobsWithTheirLastRun() {
        Session admin = fixtures.signInAdmin();
        fixtures.agent(admin);
        Instant due = clock.advance(Duration.ofHours(25)).truncatedTo(ChronoUnit.MICROS);
        Session later = fixtures.signInAdmin();

        JsonNode jobs = Wait.until(CleanupRecordTest.SESSIONS + " in the status with a run at or after " + due,
                Duration.ofSeconds(90), () -> {
                    JsonNode listed = Delivery.status(later).path("cleanupJobs");
                    for (JsonNode job : listed) {
                        if (CleanupRecordTest.SESSIONS.equals(job.path("name").asString(null))
                                && job.path("lastRunAt").isString()
                                && !OpsChecks.instant(job.path("lastRunAt"), "lastRunAt").isBefore(due)) {
                            return listed;
                        }
                    }
                    return null;
                });

        List<String> names = new ArrayList<>();
        for (JsonNode job : jobs) {
            assertThat(fieldNames(job)).as("fields of %s", job).isEqualTo(Set.of("name", "lastRunAt", "deleted"));
            assertThat(job.path("name").asString("")).as("name of %s", job).isNotBlank();
            assertThat(job.path("lastRunAt").isNull()).as("lastRunAt and deleted are null together in %s", job)
                    .isEqualTo(job.path("deleted").isNull());
            if (!job.path("lastRunAt").isNull()) {
                OpsChecks.instant(job.path("lastRunAt"), "lastRunAt of " + job);
                assertThat(job.path("deleted").isIntegralNumber() && job.path("deleted").asLong() >= 0)
                        .as("deleted is a count in %s", job).isTrue();
            }
            names.add(job.path("name").asString(""));
            if (CleanupRecordTest.SESSIONS.equals(job.path("name").asString(null))) {
                assertThat(job.path("deleted").asLong(-1)).as("ended sessions of the admin and the agent, in %s",
                        job).isGreaterThanOrEqualTo(2);
            }
        }
        assertThat(names).as("job names").doesNotHaveDuplicates().isSorted();
    }
}
