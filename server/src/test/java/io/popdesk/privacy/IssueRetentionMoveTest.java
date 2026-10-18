package io.popdesk.privacy;

import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import io.popdesk.trash.TrashJobs;
import io.popdesk.trash.TrashRows;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Moving the due issues to the trash (S10B-AC-20, S10B-AC-22, S10B-AC-23): only {@code CLOSED} issues closed more
 * than 90 days ago that are not in the trash, at most once a day, as "automatic cleanup" without an account, with a
 * {@code TRASH} history entry by popdesk and no audit entry; the trash purges them 30 days later as it purges any.
 *
 * <p>One company of the test holds every kind of issue: the due one, one closed 89 days before the run, one closed
 * so that it becomes due 7 hours after the run, a reopened one, an open one, and one an admin deleted after it was
 * closed. The admin's delete is 29 days before the run, so the trash purge has not taken it yet.
 */
class IssueRetentionMoveTest extends RetentionAppTest {

    @Test
    @DisplayName("S10B-AC-20, S10B-AC-22, S10B-AC-23 issue-retention moves to the trash only the CLOSED issues closed "
            + "more than 90 days ago that are not in the trash: the trash lists them with deletedBy null, the history "
            + "ends in a TRASH entry by popdesk, no audit entry is left, and the run's record counts them; a run 7 "
            + "hours later moves nothing, not even an issue due by then, which the run a day later moves; 30 days "
            + "after the move trash-purge purges it")
    void movesOnlyTheDueIssuesOnceADay() {
        World w = flow.world();
        String code = w.code();
        Issue due = closedIssue(w.reporter(), w.agent(), code);
        Instant dueClosedAt = closedAt(w.agent(), due);
        Issue reopened = closedIssue(w.reporter(), w.agent(), code);
        flow.reopen(w.agent(), reopened.key());
        Issue open = fixtures.fileIssue(w.reporter(), code);
        Issue deletedByAdmin = closedIssue(w.reporter(), w.agent(), code);

        clock.advance(Duration.ofHours(30));
        Session reporter = fixtures.signInAgain(w.reporter());
        Session agent = fixtures.signInAgain(w.agent());
        Issue dueLater = closedIssue(reporter, agent, code);
        Instant laterClosedAt = closedAt(agent, dueLater);
        clock.advance(Duration.ofHours(18));
        Issue recent = closedIssue(fixtures.signInAgain(reporter), fixtures.signInAgain(agent), code);

        clock.advance(Duration.between(clock.instant(), dueClosedAt.plus(Duration.ofDays(62))));
        Session admin = fixtures.signInAdmin();
        Instant adminDeletedAt = flow.tick();
        TrashSteps.delete(admin, deletedByAdmin.key());

        Instant moved = dueClosedAt.plus(PERIOD).plus(Duration.ofDays(1));
        JsonNode run = runAt(moved);
        admin = fixtures.signInAdmin();

        assertThat(TrashJobs.count(run)).as("count of the run at %s: %s", moved, run).isPositive();
        assertMovedByCleanup(admin, due, moved);
        for (Issue kept : List.of(reopened, open, recent, dueLater)) {
            assertThat(TrashSteps.entry(admin, kept.key())).as("trash entry of %s after the run", kept.key()).isNull();
            assertThat(flow.detail(admin, kept.key()).path("key").asString(null)).as("%s after the run", kept.key())
                    .isEqualTo(kept.key());
        }
        assertThat(flow.detail(admin, reopened.key()).path("status").asString(null)).as("status of the reopened issue")
                .isEqualTo("RECEIVED");
        JsonNode byAdmin = TrashSteps.entry(admin, deletedByAdmin.key());
        assertThat(byAdmin).as("trash entry of the issue the admin deleted").isNotNull();
        assertThat(byAdmin.path("deletedBy").path("accountId").asLong(-1)).as("deletedBy of %s", byAdmin)
                .isEqualTo(admin.accountId());
        assertThat(time(byAdmin, "deletedAt")).as("deletedAt of %s", byAdmin).isEqualTo(adminDeletedAt);
        assertThat(auditEntries(admin, code, "ISSUE_DELETED")).as("ISSUE_DELETED entries of the company: the admin's "
                + "delete only").isEqualTo(1);
        JsonNode exportExpiry = TrashJobs.awaitRun(admin, EXPORT_EXPIRY, moved);
        assertThat(TrashJobs.count(exportExpiry)).as("count of %s with no export: %s", EXPORT_EXPIRY, exportExpiry)
                .isZero();

        Instant sameDay = laterClosedAt.plus(PERIOD).plus(Duration.ofHours(1));
        assertThat(sameDay).as("the next run, within a day of the move").isBefore(moved.plus(WAIT));
        JsonNode next = runAt(sameDay);
        admin = fixtures.signInAdmin();
        assertThat(TrashJobs.count(next)).as("count of the run at %s, within a day of the move: %s", sameDay, next)
                .isZero();
        assertThat(TrashSteps.entry(admin, dueLater.key())).as("trash entry of %s, due at the run within the day",
                dueLater.key()).isNull();

        Instant nextDay = moved.plus(WAIT).plus(Duration.ofMinutes(1));
        JsonNode daily = runAt(nextDay);
        admin = fixtures.signInAdmin();
        assertThat(TrashJobs.count(daily)).as("count of the run a day after the move: %s", daily).isPositive();
        assertMovedByCleanup(admin, dueLater, nextDay);

        Instant purgeDue = moved.plus(Duration.ofDays(TrashSteps.PURGE_AFTER_DAYS)).plus(Duration.ofHours(1));
        clock.advance(Duration.between(clock.instant(), purgeDue));
        Session afterPurge = fixtures.signInAdmin();
        TrashJobs.awaitRun(afterPurge, TrashJobs.PURGE, purgeDue);
        Wait.untilAsserted("the issue moved by the cleanup is purged 30 days later", () -> {
            assertThat(TrashSteps.entry(afterPurge, due.key())).as("trash entry of %s", due.key()).isNull();
            assertThat(rows.issue(due.key()).get("title")).as("title of %s", due.key()).isEqualTo("");
        });
        assertThat(TrashSteps.entry(afterPurge, dueLater.key())).as("trash entry of %s, moved a day later",
                dueLater.key()).isNotNull();
    }

    /**
     * The issue is in the trash as the cleanup moved it at {@code at}: the entry with deletedBy null, the time, and
     * the purge 30 days later; the history's last entry by popdesk (account 0); every issue path 404.
     */
    private void assertMovedByCleanup(Session admin, Issue issue, Instant at) {
        JsonNode entry = TrashSteps.entry(admin, issue.key());
        assertThat(entry).as("trash entry of %s after the run at %s", issue.key(), at).isNotNull();
        assertThat(entry.has("deletedBy")).as("deletedBy in %s", entry).isTrue();
        assertThat(entry.path("deletedBy").isNull()).as("deletedBy of %s: automatic cleanup", entry).isTrue();
        assertThat(time(entry, "deletedAt")).as("deletedAt of %s", entry).isEqualTo(at);
        assertThat(time(entry, "purgeAt")).as("purgeAt of %s", entry)
                .isEqualTo(at.plus(Duration.ofDays(TrashSteps.PURGE_AFTER_DAYS)));
        assertThat(entry.path("status").asString(null)).as("status of %s", entry).isEqualTo("CLOSED");
        Map<String, Object> last = rows.history(issue.key()).getLast();
        assertThat(last.get("field")).as("field of %s", last).isEqualTo(TrashSteps.FIELD);
        assertThat(last.get("old_value")).as("old value of %s", last).isNull();
        assertThat(last.get("new_value")).as("new value of %s", last).isEqualTo(TrashSteps.TRASHED);
        assertThat(((Number) last.get("actor_id")).longValue()).as("actor of %s: popdesk", last).isZero();
        assertThat(TrashRows.instant(last.get("created_at"))).as("time of %s", last).isEqualTo(at);
        ApiResponse detail = admin.client().get(TrashSteps.issuePath(issue.key())).send();
        assertThat(detail.status()).as("the issue %s in the trash: %s", issue.key(), detail.snippet()).isEqualTo(404);
        assertThat(detail.errorCode()).as("code for the issue in the trash").isEqualTo("NOT_FOUND");
    }
}
