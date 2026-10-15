package io.popdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * A restore and a purge of the same issue at the same time (S7A-AC-34, TRASH-2, TRASH-3, TM-61), made to overlap as
 * {@code acceptance.md} 4절 says: the test holds the issue row {@code FOR UPDATE} on a connection of its own
 * ({@link RowLock}), sends the first request (or moves the app clock past the 30 days, for the {@code trash-purge}
 * job) and waits until it reaches the row, sends the second, and lets the row go once the second waits too (or a
 * short time passed).
 *
 * <p>Which one wins is not fixed. The end is one of two: the restore won, so the issue is live and everything is as
 * it was (the purge, if a person sent it, is 404); or the purge won, so the restore is 404 and the issue is empty.
 * A purge that neither locks the row nor checks again that the issue is still in the trash empties the issue the
 * restore just brought back: a live issue without its content.
 */
class TrashPurgeRaceTest extends BareAppTest {

    /** How long the row is held when the second step neither waits for it nor finishes. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(3);

    @Autowired
    PostgreSQLContainer postgres;

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    /** The two steps and which reaches the held row first. */
    enum Race {
        RESTORE_THEN_JOB,
        RESTORE_THEN_PURGE,
        PURGE_THEN_RESTORE
    }

    private static void holdUntil(RowLock lock, long waiting, BooleanSupplier done) {
        long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
        Wait.until("the second step waits for the row, finished, or had " + HELD_AT_MOST,
                () -> lock.waitingForALock() >= waiting || done.getAsBoolean() || System.nanoTime() > heldUntil
                        ? Boolean.TRUE : null);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Race.class)
    @DisplayName("S7A-AC-34 a restore that meets an admin's purge or the trash-purge job ends as if one ran first: the restored issue is live with everything as it was and the purge is 404, or the issue is purged and the restore is 404; never a live issue that was emptied")
    void restoreMeetsPurge(Race race) {
        TrashScene s = TrashScene.build(fixtures, flow, clock, "IN_PROGRESS");
        String key = s.key();
        Map<String, Object> fields = rows.fields(key);
        Map<String, List<Map<String, Object>>> under = rows.under(key);
        TrashSteps.delete(s.admin(), key);
        Session admin = s.admin();
        ApiResponse restore;
        ApiResponse purge = null;
        Instant due = null;

        try (RowLock lock = new RowLock(db, postgres, rows.id(key))) {
            Session sender = admin;
            switch (race) {
                case RESTORE_THEN_JOB -> {
                    CompletableFuture<ApiResponse> restoring = CompletableFuture.supplyAsync(
                            () -> TrashSteps.restoreRequest(sender, key).send());
                    lock.awaitWaiting(1, "the restore");
                    due = clock.advance(Duration.ofDays(TrashSteps.PURGE_AFTER_DAYS).plusMinutes(1));
                    admin = fixtures.signInAgain(sender);
                    Session fresh = admin;
                    Instant since = due;
                    holdUntil(lock, 2, () -> TrashJobs.run(fresh, TrashJobs.PURGE, since) != null);
                    lock.release();
                    restore = Concurrently.finish(restoring, "the restore");
                }
                case RESTORE_THEN_PURGE -> {
                    CompletableFuture<ApiResponse> restoring = CompletableFuture.supplyAsync(
                            () -> TrashSteps.restoreRequest(sender, key).send());
                    lock.awaitWaiting(1, "the restore");
                    CompletableFuture<ApiResponse> purging = CompletableFuture.supplyAsync(
                            () -> TrashSteps.purgeRequest(sender, key).send());
                    holdUntil(lock, 2, purging::isDone);
                    lock.release();
                    restore = Concurrently.finish(restoring, "the restore");
                    purge = Concurrently.finish(purging, "the purge");
                }
                case PURGE_THEN_RESTORE -> {
                    CompletableFuture<ApiResponse> purging = CompletableFuture.supplyAsync(
                            () -> TrashSteps.purgeRequest(sender, key).send());
                    lock.awaitWaiting(1, "the purge");
                    CompletableFuture<ApiResponse> restoring = CompletableFuture.supplyAsync(
                            () -> TrashSteps.restoreRequest(sender, key).send());
                    holdUntil(lock, 2, restoring::isDone);
                    lock.release();
                    purge = Concurrently.finish(purging, "the purge");
                    restore = Concurrently.finish(restoring, "the restore");
                }
                default -> throw new AssertionError(race);
            }
        }
        if (due != null) {
            TrashJobs.awaitRun(admin, TrashJobs.PURGE, due);
        }

        assertThat(restore.status()).as("restore: " + restore.snippet()).isIn(200, 404);
        if (restore.status() == 200) {
            if (purge != null) {
                assertThat(purge.status()).as("purge after the restore: " + purge.snippet()).isEqualTo(404);
                assertThat(purge.errorCode()).as("code of the purge after the restore").isEqualTo("NOT_FOUND");
            }
            JsonNode detail = flow.detail(admin, key);
            assertThat(detail.path("title").asString(null)).as("title of the restored issue").isEqualTo(s.title());
            assertThat(rows.fields(key)).as("the restored issue's own fields").isEqualTo(fields);
            assertThat(rows.under(key)).as("what is under the restored issue").isEqualTo(under);
            assertThat(TrashSteps.entry(admin, key)).as("trash entry of the restored issue").isNull();
        } else {
            assertThat(restore.errorCode()).as("code of the restore after the purge").isEqualTo("NOT_FOUND");
            if (purge != null) {
                assertThat(purge.status()).as("purge: " + purge.snippet()).isEqualTo(204);
            }
            assertThat(rows.issue(key).get("title")).as("title of the purged issue").isEqualTo("");
            assertThat(rows.issue(key).get("content")).as("content of the purged issue").isEqualTo("");
            Session reader = admin;
            Wait.untilAsserted("what was under the purged issue is gone", () -> {
                for (Map.Entry<String, List<Map<String, Object>>> kind : rows.under(key).entrySet()) {
                    assertThat(kind.getValue()).as(kind.getKey() + " of the purged issue").isEmpty();
                }
                assertThat(TrashSteps.entry(reader, key)).as("trash entry of the purged issue").isNull();
            });
            assertThat(flow.detail(admin, s.other().key()).path("key").asString(null)).as("the other issue")
                    .isEqualTo(s.other().key());
        }
    }
}
