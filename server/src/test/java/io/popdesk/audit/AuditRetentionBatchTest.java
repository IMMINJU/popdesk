package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.assertChain;
import static io.popdesk.audit.AuditSteps.ended;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.ChainSteps.awaitRetentionRun;
import static io.popdesk.audit.ChainSteps.chain;
import static io.popdesk.audit.RetentionBatchSteps.BATCH;
import static io.popdesk.audit.RetentionBatchSteps.anchor;
import static io.popdesk.audit.RetentionBatchSteps.awaitWaitingOrAnswered;
import static io.popdesk.audit.RetentionBatchSteps.rename;
import static io.popdesk.audit.RetentionBatchSteps.runRecord;
import static io.popdesk.audit.RetentionBatchSteps.seqs;
import static io.popdesk.audit.RetentionBatchSteps.upTo;
import static io.popdesk.privacy.PrivacySteps.anonymizeRequest;
import static io.popdesk.privacy.PrivacySteps.anonymizedAccount;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static io.popdesk.support.AuditChainMath.assertValid;
import static io.popdesk.support.AuditChainMath.hash;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.audit.RetentionBatchSteps.Anchor;
import io.popdesk.audit.RetentionBatchSteps.Front;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppLog;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.FailedWrite;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The retention job in batches (slice b7, B7-AC-01 to B7-AC-06): it deletes the old front of the chain 1,000
 * entries at a time, each batch in a transaction of its own that also moves where the chain starts.
 *
 * <p>Like {@link AuditRetentionTest}, these tests move the clock a year and share the context of
 * {@link AuditTamperTest}. Each makes its own old entries, more than a batch, by renaming an account
 * ({@link RetentionBatchSteps#rename}), and reads what is deleted from the chain as it was just before the run
 * ({@link RetentionBatchSteps#upTo}). A batch is held or made to fail at the delete of the first entry of the
 * second batch: the starting point + 1,001.
 *
 * <p>That one transaction per batch (B7-AC-01) holds is seen from outside: the first batch is committed while
 * the second is held (B7-AC-02), and stays deleted when the second fails (B7-AC-04).
 */
@TestPropertySource(properties = AuditTamperTest.OWN_CONTEXT)
class AuditRetentionBatchTest extends BareAppTest {

    private static final String FAILED = "Scheduled work '" + ChainSteps.RETENTION_JOB + "' failed";

    @Autowired
    PostgreSQLContainer postgres;

    /** {@code renames} renames of {@code target} by {@code renamer}, and the chain before the run that deletes them. */
    private record Old(Session renamer, long target, Front front) {

        Session admin() {
            return front.admin();
        }

        /** The renames' entries (and the target's invitation) with a {@code seq} above {@code seq}, as before the run. */
        List<Long> ofTargetAfter(long seq) {
            return front.lines().stream()
                    .filter(line -> line.path("target").path("accountId").asLong(-1) == target)
                    .map(line -> number(line, "seq")).filter(s -> s > seq).sorted().toList();
        }
    }

    /**
     * Makes {@code renames} entries now and moves the clock to 6 hours before a run at a year and 12 hours
     * from now, which deletes them all; the run itself is {@code front().run(clock)}.
     */
    private Old old(int renames, long atLeast) {
        Session admin = fixtures.signInAdmin();
        Session renamer = fixtures.admin(admin);
        Session target = fixtures.agent(admin);
        rename(renamer, target.accountId(), renames);
        Instant runAt = clock.instant().plus(AuditRetentionTest.PERIOD).plus(Duration.ofHours(12));
        return new Old(renamer, target.accountId(), upTo(clock, db, fixtures, runAt, atLeast));
    }

    /** The anchor as it is after a batch that ended at {@code seq}: that entry and its hash. */
    private static Anchor anchorAt(Front front, long seq) {
        return new Anchor(seq, text(front.at(seq), "hash"));
    }

    /** After a run that deleted the whole front: the chain goes on from the last deleted entry, and is whole. */
    private static void assertGoesOnFrom(Session admin, long endSeq, String endHash) {
        List<JsonNode> chain = chain(admin);
        assertThat(number(chain.getFirst(), "seq")).as("seq of the first line of the export after the run")
                .isEqualTo(endSeq + 1);
        assertThat(text(chain.getFirst(), "prevHash")).as("prevHash of the first line of the export after the run")
                .isEqualTo(endHash);
        assertChain(chain);
        assertValid(admin.client());
    }

    @Test
    @DisplayName("B7-AC-01, B7-AC-02 while the job deletes its second batch, the first 1,000 entries are deleted "
            + "and committed: another request's audit list does not have them, the integrity check says valid, the "
            + "chain starts at the last entry of the first batch; after the run the export's first line has the hash "
            + "of the last deleted entry as its previous hash")
    void firstBatchIsCommittedWhileTheSecondRuns() {
        Old old = old(BATCH + 100, BATCH);
        Front front = old.front();
        long held = front.batchEnd(1) + 1;

        try (HeldWrite hold = new HeldWrite(db, postgres, "audit_log", "delete", text(front.at(held), "hash"))) {
            front.run(clock);
            hold.awaitHeld("the job's delete of seq " + held + ", the first of its second batch,");

            assertThat(seqs(old.admin(), "targetAccountId", old.target()))
                    .as("seq of the renames in the audit list while the second batch is held; the first batch "
                            + "ended at %d", front.batchEnd(1))
                    .isEqualTo(old.ofTargetAfter(front.batchEnd(1)));
            assertValid(old.admin().client());
            assertThat(anchor(db)).as("where the chain starts while the second batch is held")
                    .isEqualTo(anchorAt(front, front.batchEnd(1)));
            hold.release();
        }
        awaitRetentionRun(db, front.runAt());

        assertGoesOnFrom(old.admin(), front.endSeq(), text(front.at(front.endSeq()), "hash"));
    }

    @Test
    @DisplayName("B7-AC-03 with more than 1,000 entries over the period, one run deletes them all; deleted_count is "
            + "how many it deleted, the integrity check says valid, and the first entry left has the hash of the "
            + "last deleted one as its previous hash")
    void oneRunDeletesMoreThanABatch() {
        Old old = old(BATCH + 100, BATCH);
        Front front = old.front();

        Map<String, Object> run = awaitRetentionRun(db, front.run(clock));

        assertThat(ChainSteps.lastRun(run)).as("last_run_at of the job's row").isEqualTo(front.runAt());
        assertThat(((Number) run.get("deleted_count")).longValue()).as("deleted_count of the job's row")
                .isEqualTo(front.count());
        assertThat(seqs(old.admin(), "targetAccountId", old.target())).as("seq of the renames left after the run")
                .isEmpty();
        assertGoesOnFrom(old.admin(), front.endSeq(), text(front.at(front.endSeq()), "hash"));
    }

    @Test
    @DisplayName("B7-AC-01, B7-AC-04 when the second batch fails in the database, the first batch stays deleted and "
            + "the second is rolled back; the run is not recorded, the integrity check says valid, and once the "
            + "cause is gone the next run deletes the rest")
    void failedBatchLeavesTheOnesBeforeIt() {
        Old old = old(BATCH + 100, BATCH);
        Front front = old.front();
        Map<String, Object> before = runRecord(db);
        long failing = front.batchEnd(1) + 1;
        AppLog.Mark mark = log.mark();

        // A plain raise: SQLSTATE P0001, which Transactions.run does not try again (unlike 55P03 and 40P01).
        try (FailedWrite fail = FailedWrite.statement(db, "audit_log", "delete", text(front.at(failing), "hash"))) {
            front.run(clock);
            log.awaitLine(mark, FAILED);
        }

        assertThat(seqs(old.admin(), "targetAccountId", old.target()))
                .as("seq of the renames in the audit list after the second batch failed; the first batch ended at %d",
                        front.batchEnd(1))
                .isEqualTo(old.ofTargetAfter(front.batchEnd(1)));
        assertThat(anchor(db)).as("where the chain starts after the second batch failed")
                .isEqualTo(anchorAt(front, front.batchEnd(1)));
        assertThat(runRecord(db)).as("the job's row after the failed run").isEqualTo(before);
        assertValid(old.admin().client());

        Map<String, Object> next = awaitRetentionRun(db, clock.advance(Duration.ofMinutes(1)));

        assertThat(((Number) next.get("deleted_count")).longValue()).as("deleted_count of the next run")
                .isEqualTo(front.count() - BATCH);
        assertThat(seqs(old.admin(), "targetAccountId", old.target())).as("seq of the renames left after the next run")
                .isEmpty();
        assertGoesOnFrom(old.admin(), front.endSeq(), text(front.at(front.endSeq()), "hash"));
    }

    @Test
    @DisplayName("B7-AC-05 in batches too, an entry over the period that was chained after an entry inside it stays, "
            + "with the one inside it, and everything before them is deleted in one run")
    void entryBehindOneInsideThePeriodStaysInBatches() {
        Session admin = fixtures.signInAdmin();
        Session renamer = fixtures.admin(admin);
        Session other = fixtures.admin(admin);
        Session agent = fixtures.agent(admin);
        Session target = fixtures.agent(admin);
        rename(renamer, target.accountId(), BATCH + 100);
        Instant over = clock.instant();
        Company company;
        ExecutorService thread = Executors.newSingleThreadExecutor();
        // As in AuditRetentionTest: the suspension takes its time when it is recorded, then ends the agent's
        // sessions; held there, it commits after a company that was created 2 seconds later.
        try (HeldWrite hold = new HeldWrite(db, postgres, "spring_session", "delete",
                Long.toString(agent.accountId()))) {
            Future<ApiResponse> suspending = thread.submit(() -> admin.client()
                    .post("/api/console/accounts/" + agent.accountId() + "/suspend").send());
            hold.awaitHeld("the suspension, ending the agent's sessions,");
            clock.advance(Duration.ofSeconds(2));
            company = fixtures.createCompany(other);
            hold.release();
            assertThat(ended(suspending).status()).as("status of the held suspension").isEqualTo(200);
        } finally {
            thread.shutdownNow();
        }
        JsonNode suspended = onlyEntry(admin, "targetAccountId", agent.accountId(), "action", "ACCOUNT_SUSPENDED");
        JsonNode created = onlyEntry(admin, "targetCode", company.code(), "action", "COMPANY_CREATED");
        assertThat(number(suspended, "seq")).as("seq of the suspension, chained after the company")
                .isEqualTo(number(created, "seq") + 1);
        // The period ends 1 second after the renames and the suspension, 1 second before the company.
        Front front = upTo(clock, db, fixtures, over.plus(AuditRetentionTest.PERIOD).plusSeconds(1), BATCH);
        assertThat(front.endSeq()).as("the last entry the run deletes: the one before the company")
                .isEqualTo(number(created, "seq") - 1);

        Map<String, Object> run = awaitRetentionRun(db, front.run(clock));

        assertThat(chain(front.admin()).stream().limit(2).map(line -> number(line, "id")).toList())
                .as("the first two entries left: the company inside the period, then the suspension behind it")
                .containsExactly(number(created, "id"), number(suspended, "id"));
        assertThat(((Number) run.get("deleted_count")).longValue()).as("deleted_count of the job's row")
                .isEqualTo(front.count());
        assertThat(seqs(front.admin(), "targetAccountId", target.accountId())).as("seq of the renames left")
                .isEmpty();
        assertValid(front.admin().client());
    }

    @Test
    @DisplayName("B7-AC-06 an anonymization sent while the job holds its second batch finishes without 409 between "
            + "the batches; after the run the integrity check says valid, and the first entry left has the hash of "
            + "the last deleted entry as computed again by the anonymization")
    void anonymizationBetweenBatches() {
        Old old = old(2 * BATCH + 100, 2 * BATCH);
        Front front = old.front();
        long person = old.renamer().accountId();
        long held = front.batchEnd(1) + 1;
        ApiResponse answer;
        ExecutorService thread = Executors.newSingleThreadExecutor();

        try (HeldWrite hold = new HeldWrite(db, postgres, "audit_log", "delete", text(front.at(held), "hash"))) {
            front.run(clock);
            hold.awaitHeld("the job's delete of seq " + held + ", the first of its second batch,");
            Future<ApiResponse> anonymizing = thread.submit(() -> anonymizeRequest(front.admin(), person).send());
            awaitWaitingOrAnswered("the anonymization of account " + person, anonymizing,
                    () -> hold.waitingForALock() > 0);
            hold.release();
            answer = ended(anonymizing);
        } finally {
            thread.shutdownNow();
        }

        assertThat(answer.status()).as("status of the anonymization sent during the second batch: %s",
                answer.snippet()).isEqualTo(200);
        awaitRetentionRun(db, front.runAt());
        assertValid(front.admin().client());
        // The anonymization came in after the second batch: it computed the chain again from the entry after
        // the second batch's last, with the anonymous values in the person's renames.
        JsonNode anonymous = anonymizedAccount(front.admin(), person);
        String recomputed = text(front.at(front.batchEnd(2)), "hash");
        for (JsonNode line : front.between(front.batchEnd(2) + 1, front.endSeq())) {
            assertThat(line.path("target").path("accountId").asLong(-1)).as("target of %s", line).isNotEqualTo(person);
            recomputed = hash(recomputed, withActor(line, person, text(anonymous, "name"), anonymousEmail(person)));
        }
        assertThat(recomputed).as("the last deleted entry's hash computed again, against the one before")
                .isNotEqualTo(text(front.at(front.endSeq()), "hash"));
        assertGoesOnFrom(front.admin(), front.endSeq(), recomputed);
    }

    /** The line with this name and email in its actor when the actor is the account. */
    private static JsonNode withActor(JsonNode line, long accountId, String name, String email) {
        JsonNode copy = line.deepCopy();
        if (copy.path("actor").path("accountId").asLong(-1) == accountId) {
            ((ObjectNode) copy.get("actor")).put("name", name).put("email", email);
        }
        return copy;
    }
}
