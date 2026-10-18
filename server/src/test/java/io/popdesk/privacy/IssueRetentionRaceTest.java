package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import io.popdesk.trash.RowLock;
import io.popdesk.trash.TrashJobs;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An admin deletes a due issue while issue-retention comes for it (S10B-AC-21, STATUS-7), made to overlap as
 * {@code trash.AutoCloseRaceTest} does: the test holds the issue row {@code FOR UPDATE} ({@link RowLock}), sends the
 * delete and waits until it reaches the row, moves the clock past the 90 days so that the job runs while the delete
 * has not committed, and lets the row go once the job waits for it too or finished.
 *
 * <p>The delete reached the row first, so the issue is in the trash as the admin deleted it, once. A job that picks
 * the due issues and then writes the delete without its conditions in the UPDATE waits behind the admin's delete and
 * deletes the issue again, as automatic cleanup. Reopening cannot meet the job: a {@code CLOSED} issue can be reopened
 * only within 14 days of its closing ({@code NOT_REOPENABLE}), long before it is due; that a reopened issue is not
 * moved is in {@link IssueRetentionMoveTest}.
 */
class IssueRetentionRaceTest extends RetentionAppTest {

    @Test
    @DisplayName("S10B-AC-21 an admin's delete that meets issue-retention ends as if it ran first: the issue is in the "
            + "trash deleted by the admin, with one TRASH entry in its history")
    void adminsDeleteMeetsTheCleanup() {
        World w = flow.world();
        Issue issue = closedIssue(w.reporter(), w.agent(), w.code());
        String key = issue.key();
        Instant closed = closedAt(w.agent(), issue);
        Session admin = fixtures.admin(w.admin());
        long trashEntries = rows.history(key).stream().filter(r -> TrashSteps.FIELD.equals(r.get("field"))).count();
        ApiResponse delete;
        Instant due;

        try (RowLock lock = new RowLock(db, postgres, rows.id(key))) {
            CompletableFuture<ApiResponse> deleting = CompletableFuture.supplyAsync(
                    () -> TrashSteps.deleteRequest(admin, key).send());
            lock.awaitWaiting(1, "the admin's delete");
            due = clock.advance(Duration.between(clock.instant(), closed.plus(PERIOD).plus(Duration.ofDays(1))));
            Session fresh = fixtures.signInAdmin();
            Instant since = due;
            Wait.until("the job waits for the row or finished", TrashJobs.RUN,
                    () -> lock.waitingForALock() >= 2 || TrashJobs.run(fresh, JOB, since) != null
                            ? Boolean.TRUE : null);
            lock.release();
            delete = Concurrently.finish(deleting, "the admin's delete");
        }
        Session after = fixtures.signInAgain(admin);
        TrashJobs.awaitRun(after, JOB, due);

        assertThat(delete.status()).as("the admin's delete: " + delete.snippet()).isEqualTo(204);
        JsonNode entry = TrashSteps.entry(after, key);
        assertThat(entry).as("trash entry of %s", key).isNotNull();
        assertThat(entry.path("deletedBy").path("accountId").asLong(-1)).as("deletedBy of %s", entry)
                .isEqualTo(admin.accountId());
        assertThat(rows.history(key).stream().filter(r -> TrashSteps.FIELD.equals(r.get("field"))).count())
                .as("TRASH entries in the history of %s", key).isEqualTo(trashEntries + 1);
    }
}
