package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Reopening a resolved issue while the automatic closing comes for it (S7A-AC-42, STATUS-5, STATUS-7), made to
 * overlap as {@code acceptance.md} 4절 says: the test holds the issue row {@code FOR UPDATE} on a connection of its own
 * ({@link RowLock}), sends the reopening and waits until it reaches the row, moves the app clock past the 7 days so
 * that {@code issue-auto-close} runs while the reopening has not committed, and lets the row go once the job waits
 * for it too (or a short time passed).
 *
 * <p>The reopening reached the row first, so the issue ends {@code RECEIVED}. A job that reads the resolved issues
 * and then writes {@code CLOSED} without its conditions in the UPDATE reads the issue still {@code RESOLVED}, waits
 * behind the reopening, and closes the reopened issue.
 */
class AutoCloseRaceTest extends BareAppTest {

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

    /** Who reopens. */
    enum Reopener {
        CUSTOMER,
        STAFF
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Reopener.class)
    @DisplayName("S7A-AC-42 a reopening that meets the automatic closing ends as if one ran first: the reopened issue is RECEIVED and its history does not end in a CLOSED after the reopening")
    void reopeningMeetsTheAutoClose(Reopener who) {
        World w = flow.world();
        Issue issue = flow.fileIn(w.reporter(), NewIssue.in(w.code()), w.agent(), "RESOLVED");
        String key = issue.key();
        Instant resolvedAt = time(flow.detail(w.agent(), key), "resolvedAt");
        Session reopener = who == Reopener.CUSTOMER ? w.reporter() : w.agent();
        int entries = rows.history(key).size();
        ApiResponse reopening;
        Instant due;
        Session admin;

        try (RowLock lock = new RowLock(db, postgres, rows.id(key))) {
            CompletableFuture<ApiResponse> reopen = CompletableFuture.supplyAsync(
                    () -> IssueFlow.reopenRequest(reopener, key).send());
            lock.awaitWaiting(1, "the reopening");
            due = clock.advance(Duration.between(clock.instant(),
                    resolvedAt.plus(Duration.ofDays(7).plusMinutes(11))));
            admin = fixtures.signInAgain(w.admin());
            Session fresh = admin;
            Instant since = due;
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the job waits for the row, finished, or had " + HELD_AT_MOST,
                    () -> lock.waitingForALock() >= 2 || TrashJobs.run(fresh, TrashJobs.AUTO_CLOSE, since) != null
                            || System.nanoTime() > heldUntil ? Boolean.TRUE : null);
            lock.release();
            reopening = Concurrently.finish(reopen, "the reopening");
        }
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, due);

        assertThat(reopening.status()).as("reopening: " + reopening.snippet()).isEqualTo(200);
        assertThat(flow.detail(admin, key).path("status").asString(null)).as("status after both")
                .isEqualTo("RECEIVED");
        List<Map<String, Object>> statusEntries = rows.history(key).stream().skip(entries)
                .filter(r -> "STATUS".equals(r.get("field"))).toList();
        assertThat(statusEntries).as("STATUS entries after the issue was resolved").isNotEmpty();
        assertThat(statusEntries.getLast().get("new_value")).as("the last STATUS entry").isEqualTo("RECEIVED");
    }
}
