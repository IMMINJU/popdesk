package io.popdesk.audit;

import static io.popdesk.audit.ChainSteps.awaitRetentionRun;
import static io.popdesk.audit.ChainSteps.chain;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the tests of the retention job in batches (slice b7, B7-AC-01 to B7-AC-06): many old
 * entries made through the API, a clock that brings the job due at a chosen time with a session
 * that is still valid then, and the chain as it was just before that run.
 *
 * <p>The job runs a day after its last run, and a session ends 8 hours after sign-in, so a single
 * move of a year leaves no session to look with while the job runs. {@link #upTo} moves the clock
 * in three steps instead: to a day before the run (the job runs there and deletes what is older than
 * the period then), to 6 hours before it (a sign-in), and {@link Front#run} to the run itself.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
final class RetentionBatchSteps {

    /** The batch size of the job (acceptance.md 3절 2): a batch goes up to the starting point + 1,000. */
    static final int BATCH = 1000;

    private RetentionBatchSteps() {}

    /**
     * Renames an account {@code times} times, each a {@code PATCH} that makes one
     * {@code ACCOUNT_NAME_CHANGED} entry: the lightest request found that adds one audit entry (no
     * password hash, no limit per window). The name goes back and forth between two values.
     */
    static void rename(Session admin, long accountId, int times) {
        for (int i = 0; i < times; i++) {
            admin.client().patch("/api/console/accounts/" + accountId)
                    .json(Map.of("name", (i % 2 == 0 ? "Batch renamed A " : "Batch renamed B ") + accountId))
                    .expect(200);
        }
    }

    /**
     * The chain just before a run of the job at {@code runAt}: {@code admin} signed in 6 hours before
     * it, {@code lines} the whole chain then (a JSON Lines export), {@code anchorSeq} where the chain
     * starts ({@code audit_chain}), and {@code endSeq} the last entry the run deletes: the last of the
     * front of the chain older than the period at {@code runAt}.
     */
    record Front(Session admin, List<JsonNode> lines, long anchorSeq, long endSeq, Instant runAt) {

        /** How many entries the run deletes. */
        long count() {
            return endSeq - anchorSeq;
        }

        /** The last entry of batch {@code n} (from 1): the starting point + 1,000 n, at most the end. */
        long batchEnd(int n) {
            return Math.min(endSeq, anchorSeq + (long) BATCH * n);
        }

        /** The line of the export with this {@code seq}. */
        JsonNode at(long seq) {
            return lines.stream().filter(line -> number(line, "seq") == seq).findFirst()
                    .orElseThrow(() -> new AssertionError("No line with seq " + seq + " in the export before the run"));
        }

        /** The lines from {@code fromSeq} to {@code toSeq}, both included, in chain order. */
        List<JsonNode> between(long fromSeq, long toSeq) {
            return lines.stream().filter(line -> number(line, "seq") >= fromSeq && number(line, "seq") <= toSeq)
                    .toList();
        }

        /** Moves the clock to {@code runAt}; the job runs within a second or two. Returns the time. */
        Instant run(TestClock clock) {
            return clock.advance(Duration.between(clock.instant(), runAt));
        }
    }

    /**
     * Moves the clock to 6 hours before {@code runAt}, through a run of the job a day before it, signs
     * the first admin in, and reads the chain. Fails unless the run at {@code runAt} would delete more
     * than {@code atLeast} entries.
     */
    static Front upTo(TestClock clock, DbInspector db, Fixtures fixtures, Instant runAt, long atLeast) {
        Instant dayBefore = runAt.minus(Duration.ofDays(1));
        assertThat(dayBefore).as("a day before the run, after the clock's time now").isAfter(clock.instant());
        awaitRetentionRun(db, clock.advance(Duration.between(clock.instant(), dayBefore)));
        clock.advance(Duration.ofHours(18));
        Session admin = fixtures.signInAdmin();
        List<JsonNode> lines = chain(admin);
        long anchorSeq = anchor(db).seq();
        Instant cutoff = runAt.minus(AuditRetentionTest.PERIOD);
        assertThat(number(lines.getFirst(), "seq")).as("seq of the first line of the export, right after the anchor")
                .isEqualTo(anchorSeq + 1);
        long endSeq = anchorSeq;
        for (JsonNode line : lines) {
            if (!AuditSteps.instant(text(line, "occurredAt")).isBefore(cutoff)) {
                break;
            }
            endSeq = number(line, "seq");
        }
        Front front = new Front(admin, lines, anchorSeq, endSeq, runAt);
        assertThat(front.count()).as("entries the run at %s deletes", runAt).isGreaterThan(atLeast);
        return front;
    }

    /** Where the chain starts: {@code anchor_seq} and {@code anchor_hash} of {@code audit_chain} (V6). */
    record Anchor(long seq, String hash) {}

    static Anchor anchor(DbInspector db) {
        Map<String, Object> row = db.row("select anchor_seq, anchor_hash from audit_chain");
        return new Anchor(((Number) row.get("anchor_seq")).longValue(), (String) row.get("anchor_hash"));
    }

    /** The one row of the job in the cleanup record (V3): {@code last_run_at} and {@code deleted_count}. */
    static Map<String, Object> runRecord(DbInspector db) {
        return db.row("select last_run_at, deleted_count from cleanup_run where job = ?", ChainSteps.RETENTION_JOB);
    }

    /** The {@code seq} of the entries the query finds, lowest first. */
    static List<Long> seqs(Session admin, Object... query) {
        return AuditSteps.entries(admin, query).stream().map(entry -> number(entry, "seq")).sorted().toList();
    }

    private static final Set<Thread.State> WAITING = EnumSet.of(Thread.State.WAITING, Thread.State.TIMED_WAITING,
            Thread.State.BLOCKED);

    /**
     * Waits until a request sent on another thread has either answered, or waits inside the app: a
     * request thread of the app's server ({@code -exec-} in its name, the app runs in this JVM) is
     * parked or blocked in popdesk code, or a statement of the app waits for a lock in the database
     * ({@code lockWaits}). Without this a test that lets a held write go on could do so before the
     * request reached what it waits for.
     */
    static void awaitWaitingOrAnswered(String what, Future<?> request, BooleanSupplier lockWaits) {
        Wait.until(what + " waiting in the app, or answered", () -> request.isDone() || lockWaits.getAsBoolean()
                || Thread.getAllStackTraces().entrySet().stream().anyMatch(thread -> thread.getKey().getName()
                        .contains("-exec-") && WAITING.contains(thread.getKey().getState())
                        && Arrays.stream(thread.getValue()).anyMatch(frame -> frame.getClassName()
                                .startsWith("io.popdesk."))) ? Boolean.TRUE : null);
    }
}
