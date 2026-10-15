package io.popdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The cleanup job {@code trash-purge} (S7A-AC-31, TRASH-3, OPS-2): every hour it purges the issues deleted 30 days
 * ago or more, the way an admin's purge does, and records its run and count. The app clock moves the 30 days; the
 * job's run is read from the system status ({@link TrashJobs}).
 */
class TrashPurgeJobTest extends BareAppTest {

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    @Test
    @DisplayName("S7A-AC-31 an issue deleted 30 days less a minute ago stays in the trash as it was; within the hour after the 30 days the trash-purge job purges it like an admin's purge, with the system as actor, and its record has the run and a count")
    void purgedWithinTheHourAfterThirtyDays() {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "CONFIRMED");
        String key = s.key();
        Map<String, Object> fields = rows.fields(key);
        Map<String, List<Map<String, Object>>> under = rows.under(key);
        Instant deletedAt = flow.tick();
        TrashSteps.delete(s.admin(), key);

        Instant almost = clock.advance(Duration.ofDays(TrashSteps.PURGE_AFTER_DAYS).minusMinutes(1));
        Session admin = fixtures.signInAgain(s.admin());
        TrashJobs.awaitRun(admin, TrashJobs.PURGE, almost);
        assertThat(TrashSteps.entry(admin, key)).as("trash entry a minute before 30 days").isNotNull();
        assertThat(rows.fields(key)).as("the issue's own fields a minute before 30 days").isEqualTo(fields);
        assertThat(rows.under(key)).as("what is under the issue a minute before 30 days").isEqualTo(under);

        Instant due = clock.advance(Duration.ofHours(1));
        admin = fixtures.signInAgain(admin);
        JsonNode run = TrashJobs.awaitRun(admin, TrashJobs.PURGE, due, 1);

        assertThat(TrashJobs.lastRunAt(run)).as("the run that purged, within the hour after " + deletedAt
                + " + 30 days").isBeforeOrEqualTo(deletedAt.plus(Duration.ofDays(TrashSteps.PURGE_AFTER_DAYS))
                .plus(Duration.ofHours(1)));
        assertThat(TrashSteps.entry(admin, key)).as("trash entry after the job").isNull();
        Map<String, Object> issue = rows.issue(key);
        assertThat(issue.get("title")).as("title after the job").isEqualTo("");
        assertThat(issue.get("content")).as("content after the job").isEqualTo("");
        Wait.untilAsserted("what was under the issue is gone", () -> {
            for (Map.Entry<String, List<Map<String, Object>>> kind : rows.under(key).entrySet()) {
                assertThat(kind.getValue()).as(kind.getKey() + " after the job").isEmpty();
            }
        });
        Map<String, Object> last = rows.history(key).getLast();
        assertThat(last.get("field")).as("field of " + last).isEqualTo(TrashSteps.FIELD);
        assertThat(last.get("old_value")).as("old value of " + last).isEqualTo(TrashSteps.TRASHED);
        assertThat(last.get("new_value")).as("new value of " + last).isEqualTo(TrashSteps.PURGED);
        assertThat(((Number) last.get("actor_id")).longValue()).as("actor of " + last).isZero();
        assertThat(TrashRows.instant(last.get("created_at"))).as("time of " + last)
                .isEqualTo(TrashJobs.lastRunAt(run));
        ApiResponse restore = TrashSteps.restoreRequest(admin, key).send();
        assertThat(restore.status()).as("restore after the job: " + restore.snippet()).isEqualTo(404);
        assertThat(restore.errorCode()).as("code of the restore after the job").isEqualTo("NOT_FOUND");
    }
}
