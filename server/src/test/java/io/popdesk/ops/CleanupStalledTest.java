package io.popdesk.ops;

import static io.popdesk.ops.CleanupStall.KIND;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.notify.DeliverySteps;
import io.popdesk.ops.CleanupStall.Job;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import io.popdesk.trash.TrashJobs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The alert for a cleanup job that stopped succeeding (OPS-1, slice 7b): {@code CLEANUP_STALLED} in every active
 * admin's inbox once a job has not succeeded for its interval plus a day, handled like the other operational alerts.
 * Jobs are made to fail with {@link CleanupStall}.
 *
 * <p>Each test starts and ends with every job run at a time two days on, so no job is late and no alert is open. The
 * watcher is an admin made after that, whose inbox starts empty.
 */
class CleanupStalledTest extends BareAppTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    /** Long enough for several housekeeping rounds (one a second). */
    private static final Duration ROUNDS = Duration.ofSeconds(3);

    @Autowired
    PostgreSQLContainer postgres;

    private CleanupStall stall;
    private Instant start;
    private Session watcher;

    enum Late {
        DAILY(CleanupStall.DAILY),
        TEN_MINUTES(CleanupStall.TEN_MINUTES);

        final Job job;

        Late(Job job) {
            this.job = job;
        }
    }

    @BeforeEach
    void everyJobOnTime() {
        stall = new CleanupStall(fixtures, db, postgres, clock, log);
        start = stall.everyJobRan();
        watcher = fixtures.admin(fixtures.signInAdmin());
    }

    @AfterEach
    void leaveEveryJobOnTime() {
        stall.everyJobRan();
    }

    private List<JsonNode> items() {
        watcher = fixtures.signInAgain(watcher);
        return DeliverySteps.items(watcher, KIND);
    }

    private void awaitItems(int count, String when) {
        watcher = fixtures.signInAgain(watcher);
        DeliverySteps.awaitItems(watcher, KIND, count, when);
        Session current = watcher;
        Wait.holds(count + " " + KIND + " item(s) " + when + ", no more", ROUNDS,
                () -> DeliverySteps.items(current, KIND).size() == count);
    }

    private void holdsItems(int count, String when) {
        watcher = fixtures.signInAgain(watcher);
        Session current = watcher;
        Wait.holds(count + " " + KIND + " item(s) " + when, ROUNDS,
                () -> DeliverySteps.items(current, KIND).size() == count);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Late.class)
    @DisplayName("S7B-AC-10 a job that keeps failing gives every active admin one CLEANUP_STALLED item once its "
            + "interval and a day passed since its last success: none a day and a minute after it, none a minute "
            + "before its interval and a day, one a minute after; none for an agent or a suspended admin; actor, "
            + "issue, companyCode, task, and linkedIssue are null")
    void jobLateByItsIntervalAndADayAlertsActiveAdmins(Late late) {
        Job job = late.job;
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        Session away = fixtures.admin(admin);
        Instant last = stall.lastRun(job);
        assertThat(last).as("last success of %s", job.name()).isEqualTo(start);

        stall.failAt(last.plus(Duration.ofDays(1)).plus(MINUTE), job);
        holdsItems(0, "a day and a minute after the last success of " + job.name());
        stall.failAt(last.plus(job.stalledAfter()).minus(MINUTE), job);
        holdsItems(0, "a minute before " + job.stalledAfter() + " after the last success of " + job.name());
        IssueFlow.suspendRequest(fixtures.signInAdmin(), away.accountId()).expect(200);
        int ofFirstBefore = DeliverySteps.items(fixtures.signInAdmin(), KIND).size();
        stall.failAt(last.plus(job.stalledAfter()).plus(MINUTE), job);

        awaitItems(1, "a minute after " + job.stalledAfter() + " since the last success of " + job.name());
        JsonNode item = items().getFirst();
        for (String field : List.of("actor", "issue", "companyCode", "task", "linkedIssue")) {
            assertThat(TalkFlow.isNull(item, field)).as("%s is null in %s", field, item).isTrue();
        }
        Session first = fixtures.signInAdmin();
        assertThat(DeliverySteps.items(first, KIND)).as("%s items of the first admin, one more than before", KIND)
                .hasSize(ofFirstBefore + 1);
        assertThat(DeliverySteps.items(fixtures.signInAgain(agent), KIND)).as("%s items of an agent", KIND).isEmpty();
        new IssueFlow(fixtures, clock).reactivate(first, away.accountId());
        assertThat(DeliverySteps.items(fixtures.signInAgain(away), KIND))
                .as("%s items of an admin who was suspended at the time", KIND).isEmpty();
    }

    @Test
    @DisplayName("S7B-AC-11 while a job stays late the alert is not raised again within a day: one item 23 hours "
            + "later, a second item a day and a minute after the first")
    void lateJobAlertsOnceADay() {
        Job job = CleanupStall.TEN_MINUTES;
        Instant firstItem = start.plus(job.stalledAfter()).plus(MINUTE);

        stall.failAt(firstItem, job);
        awaitItems(1, "when " + job.name() + " became late");
        stall.failAt(firstItem.plus(Duration.ofHours(23)), job);
        holdsItems(1, "23 hours after the first item, the job still failing");
        stall.failAt(firstItem.plus(Duration.ofDays(1)).plus(MINUTE), job);

        awaitItems(2, "a day and a minute after the first item, the job still failing");
    }

    @Test
    @DisplayName("S7B-AC-11 once no job is late the alert is resolved: a job that succeeds a minute after the first "
            + "item resolves it, and another job late 23 hours and 50 minutes after that item gives a new one at once")
    void alertIsResolvedOnceEveryJobIsOnTime() {
        Job tenMinutes = CleanupStall.TEN_MINUTES;
        Job daily = CleanupStall.DAILY;
        Instant firstItem = start.plus(tenMinutes.stalledAfter()).plus(MINUTE);

        // The daily job fails too, but it is late only two days after the start.
        stall.failAt(firstItem, tenMinutes, daily);
        awaitItems(1, "when " + tenMinutes.name() + " became late");
        // A minute later the 10-minute job runs again and succeeds; the daily job fails again and is not late yet.
        Instant onTime = firstItem.plus(MINUTE);
        stall.failAt(onTime, daily);
        TrashJobs.awaitRun(fixtures.signInAdmin(), tenMinutes.name(), onTime);
        holdsItems(1, "after " + tenMinutes.name() + " succeeded again");
        stall.failAt(start.plus(daily.stalledAfter()).plus(MINUTE), daily);

        awaitItems(2, "when " + daily.name() + " became late, 23 hours and 50 minutes after the first item");
    }

    @Test
    @DisplayName("S7B-AC-11 two jobs late at the same time give one CLEANUP_STALLED item")
    void twoLateJobsGiveOneItem() {
        Job daily = CleanupStall.DAILY;

        stall.failAt(start.plus(daily.stalledAfter()).plus(MINUTE), daily, CleanupStall.TEN_MINUTES);

        awaitItems(1, "with " + daily.name() + " and " + CleanupStall.TEN_MINUTES.name() + " late");
    }
}
