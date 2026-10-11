package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.assertChain;
import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.ended;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.ChainSteps.awaitRetentionRun;
import static io.popdesk.audit.ChainSteps.chain;
import static io.popdesk.support.AuditChainMath.assertValid;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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

/**
 * The retention job: entries older than {@code POPDESK_AUDIT_RETENTION} (not set here, so the
 * default {@code P365D}) are deleted once a day from the front of the chain, and the chain stays
 * whole from the starting point the job leaves.
 *
 * <p>Each test moves the clock a year forward, which deletes every older entry of its context. So
 * these tests share the context of {@link AuditTamperTest}, not the one every bare test uses. The
 * job runs within a second or two of real time after the clock moved; the tests wait for its row in
 * the cleanup record. Sessions end over a year, so each test signs in again after the move.
 */
@TestPropertySource(properties = AuditTamperTest.OWN_CONTEXT)
class AuditRetentionTest extends BareAppTest {

    /** The default of {@code POPDESK_AUDIT_RETENTION}. */
    static final Duration PERIOD = Duration.ofDays(365);

    /**
     * What a run of the job left: an entry 1 second over the period is {@code old}, one 1 second
     * inside it is {@code kept}; {@code entriesBefore} counts every entry before the clock moved.
     */
    private record Aged(Session admin, Company oldCompany, JsonNode old, Company keptCompany, JsonNode kept,
            int entriesBefore, Instant ranAt, Map<String, Object> run) {}

    @Autowired
    PostgreSQLContainer postgres;

    /** Makes two entries 2 seconds apart and moves the clock so that the period ends between them. */
    private Aged age() {
        Session before = fixtures.admin(fixtures.signInAdmin());
        Company oldCompany = fixtures.createCompany(before);
        clock.advance(Duration.ofSeconds(2));
        Company keptCompany = fixtures.createCompany(before);
        JsonNode old = onlyEntry(before, "targetCode", oldCompany.code(), "action", "COMPANY_CREATED");
        JsonNode kept = onlyEntry(before, "targetCode", keptCompany.code(), "action", "COMPANY_CREATED");
        assertThat(Duration.between(AuditSteps.time(old), AuditSteps.time(kept))).as("time between the two entries")
                .isEqualTo(Duration.ofSeconds(2));
        int entriesBefore = entries(before).size();

        Instant ranAt = clock.advance(PERIOD.minusSeconds(1));
        Map<String, Object> run = awaitRetentionRun(db, ranAt);

        return new Aged(fixtures.signInAdmin(), oldCompany, old, keptCompany, kept, entriesBefore, ranAt, run);
    }

    @Test
    @DisplayName("S1B-AC-19 a day or more after its last run the audit-retention job deletes the entries older "
            + "than P365D, the default, and records when it ran and how many it deleted")
    void jobDeletesOldEntriesAndRecordsItsRun() {
        Aged aged = age();

        List<JsonNode> left = entries(aged.admin(), "to", aged.ranAt());

        assertThat(left.stream().map(entry -> number(entry, "id")).toList())
                .as("entries from before the job ran that are still in the log")
                .containsExactly(number(aged.kept(), "id"));
        assertThat(ChainSteps.lastRun(aged.run())).as("last_run_at of the job's row").isEqualTo(aged.ranAt());
        assertThat(((Number) aged.run().get("deleted_count")).longValue()).as("deleted_count of the job's row")
                .isEqualTo(aged.entriesBefore() - 1L);
    }

    @Test
    @DisplayName("S1B-AC-22 an entry 1 second inside the period stays as it was while one 1 second over it is "
            + "deleted: the period is a difference of UTC times")
    void entryInsideThePeriodStays() {
        Aged aged = age();

        JsonNode kept = onlyEntry(aged.admin(), "targetCode", aged.keptCompany().code());

        assertThat(canonical(kept)).as("the entry inside the period").isEqualTo(canonical(aged.kept()));
        assertThat(entries(aged.admin(), "targetCode", aged.oldCompany().code()))
                .as("entries of the company created 1 second before the period").isEmpty();
    }

    @Test
    @DisplayName("S1B-AC-21 after the job deleted entries, the first entry left still has the hash of the last "
            + "deleted one as its previous hash, in the export, and the integrity check says valid")
    void firstEntryLeftKeepsThePreviousHashOfTheDeletedOne() {
        Aged aged = age();

        List<JsonNode> chain = chain(aged.admin());

        assertThat(number(aged.kept(), "seq")).as("seq of the kept entry: right after the deleted one")
                .isEqualTo(number(aged.old(), "seq") + 1);
        assertThat(number(chain.getFirst(), "id")).as("id of the first line of the export")
                .isEqualTo(number(aged.kept(), "id"));
        assertThat(text(chain.getFirst(), "prevHash")).as("prevHash of the first line of the export")
                .isEqualTo(text(aged.old(), "hash"));
        assertChain(chain);
        assertValid(aged.admin().client());
    }

    @Test
    @DisplayName("S1B-AC-17 after the job deleted every entry, new entries go on from the last deleted one and "
            + "the integrity check says valid, counting only the entries left")
    void chainGoesOnAfterEveryEntryWasDeleted() {
        Session before = fixtures.admin(fixtures.signInAdmin());
        fixtures.createCompany(before);
        JsonNode last = entries(before).stream().max(Comparator.comparingLong(entry -> number(entry, "seq")))
                .orElseThrow(() -> new AssertionError("The audit log is empty"));
        awaitRetentionRun(db, clock.advance(PERIOD.plusSeconds(1)));
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        JsonNode answer = assertValid(admin.client());

        List<JsonNode> chain = chain(admin);
        assertThat(number(chain.getFirst(), "seq")).as("seq of the first entry after every entry was deleted")
                .isEqualTo(number(last, "seq") + 1);
        assertThat(text(chain.getFirst(), "prevHash")).as("prevHash of the first entry after every entry was deleted")
                .isEqualTo(text(last, "hash"));
        assertChain(chain);
        assertThat(chain.stream().filter(line -> company.code().equals(line.path("target").path("code")
                .asString(null))).toList()).as("lines of the company created after the job ran").hasSize(1);
        assertThat(number(answer, "checkedCount")).as("checkedCount in %s", answer).isEqualTo(chain.size());
    }

    @Test
    @DisplayName("S1B-AC-17, S1B-AC-21 an entry over the period that was chained after an entry inside it is "
            + "not deleted out of the middle: both stay, the integrity check says valid, and a day later both "
            + "are deleted")
    void entryOverThePeriodBehindOneInsideItWaits() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Session other = fixtures.admin(admin);
        Session agent = fixtures.agent(admin);
        Company company;
        ExecutorService thread = Executors.newSingleThreadExecutor();
        // The suspension takes its time when it is recorded and then ends the agent's sessions. Held
        // there, it commits after a company that was created 2 seconds later.
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
        assertThat(Duration.between(AuditSteps.time(suspended), AuditSteps.time(created)))
                .as("how much earlier the suspension happened than the company before it in the chain")
                .isEqualTo(Duration.ofSeconds(2));

        awaitRetentionRun(db, clock.advance(PERIOD.minusSeconds(1)));
        Session after = fixtures.signInAdmin();

        assertValid(after.client());
        assertThat(chain(after).stream().limit(2).map(line -> number(line, "id")).toList())
                .as("the first two entries left: the company inside the period, then the suspension behind it")
                .containsExactly(number(created, "id"), number(suspended, "id"));

        awaitRetentionRun(db, clock.advance(Duration.ofDays(1)));
        Session nextDay = fixtures.signInAdmin();

        assertValid(nextDay.client());
        assertThat(entries(nextDay, "targetCode", company.code())).as("entries of the company, a day later").isEmpty();
        assertThat(entries(nextDay, "targetAccountId", agent.accountId(), "action", "ACCOUNT_SUSPENDED"))
                .as("the suspension, a day later").isEmpty();
    }
}
