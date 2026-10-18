package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.privacy.PrivacySteps.anonymize;
import static io.popdesk.privacy.PrivacySteps.anonymizeRequest;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static io.popdesk.privacy.PrivacySteps.assertChainValid;
import static io.popdesk.privacy.PrivacySteps.chain;
import static io.popdesk.privacy.PrivacySteps.entries;
import static io.popdesk.privacy.PrivacySteps.entriesOf;
import static io.popdesk.support.AuditChainMath.chainJson;
import static io.popdesk.support.AuditChainMath.hash;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Anonymizing while other things happen (S10A-AC-19 to S10A-AC-22): the app's connection ends in the middle of the
 * recalculation, sign-ins and an admin action come during it, a second anonymization comes, the retention job runs.
 *
 * <p>An anonymization is kept inside its recalculation (design.md 0절 2, part ①) by {@link HeldWrite} on the audit
 * table: the first statement that changes the row of the person's first entry waits in a trigger until the test lets
 * it go. This rests on part ① changing the audit rows with {@code UPDATE}, in the order of {@code seq}, starting from
 * that entry.
 */
class AnonymizeRaceTest extends PrivacyAppTest {

    /** The default of {@code POPDESK_AUDIT_RETENTION}, which this context does not set. */
    private static final Duration RETENTION = Duration.ofDays(365);

    /** An anonymization of {@code person} held at the update of the person's first audit row. */
    private record Held(HeldWrite hold, CompletableFuture<ApiResponse> request) implements AutoCloseable {

        @Override
        public void close() {
            hold.close();
        }
    }

    private Held holdAnonymization(Session admin, Session person) {
        String firstRow = String.valueOf(number(entriesOf(admin, person.accountId()).getFirst(), "id"));
        HeldWrite hold = new HeldWrite(db, postgres, "audit_log", "update", firstRow);
        CompletableFuture<ApiResponse> request = CompletableFuture.supplyAsync(
                () -> anonymizeRequest(admin, person.accountId()).send());
        try {
            hold.awaitHeld("the anonymization's update of the first audit row of account " + person.accountId());
        } catch (AssertionError notHeld) {
            hold.close();
            ApiResponse answer = Concurrently.finish(request, "the anonymization that was never held");
            throw new AssertionError("The anonymization was not held in its recalculation; it answered "
                    + answer.status() + " " + answer.snippet(), notHeld);
        }
        return new Held(hold, request);
    }

    /** The account as before anonymizing: its own name and email, not anonymized, no ACCOUNT_ANONYMIZED entry. */
    private static void assertUntouched(Session admin, Session person, List<JsonNode> entriesBefore) {
        JsonNode account = PrivacySteps.account(admin, person.accountId(), person.email());
        assertThat(account.path("name").asString(null)).as("name of %s", account).isEqualTo(person.name());
        assertThat(account.path("status").asString(null)).as("status of %s", account).isEqualTo("ACTIVE");
        assertThat(account.path("anonymized").isBoolean() && !account.path("anonymized").booleanValue())
                .as("anonymized is false in %s", account).isTrue();
        List<JsonNode> now = entriesOf(admin, person.accountId());
        assertThat(now.stream().map(e -> e.path("action").asString("")).toList())
                .as("kinds of the entries of %d", person.accountId()).doesNotContain("ACCOUNT_ANONYMIZED");
        assertThat(now.stream().filter(e -> entriesBefore.stream().anyMatch(b -> number(b, "id") == number(e, "id")))
                .map(e -> chainJson(e)).toList()).as("the entries of %d", person.accountId())
                .isEqualTo(entriesBefore.stream().map(e -> chainJson(e)).toList());
    }

    // S10A-AC-19

    @Test
    @DisplayName("S10A-AC-19 when the app's database connection ends in the middle of the recalculation, the whole "
            + "anonymization is rolled back, the integrity check says all entries match, and anonymizing again works")
    void connectionEndsDuringRecalculation() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, company.code());
        List<JsonNode> before = entriesOf(admin, person.accountId());
        ApiResponse answer;

        try (Held held = holdAnonymization(admin, person)) {
            List<Map<String, Object>> sleeping = db.rows("select pid from pg_stat_activity where wait_event = "
                    + "'PgSleep' and pid <> pg_backend_pid() and datname = current_database()");
            assertThat(sleeping).as("the backend of the held anonymization").hasSize(1);
            terminate(((Number) sleeping.getFirst().get("pid")).intValue());
            answer = Concurrently.finish(held.request(), "the anonymization whose connection ended");
        }

        assertThat(answer.status()).as("status of the anonymization whose connection ended: %s", answer.snippet())
                .isGreaterThanOrEqualTo(500);
        assertUntouched(admin, person, before);
        assertChainValid(admin);
        assertThat(anonymize(admin, person.accountId()).path("anonymized").asBoolean(false))
                .as("anonymized after trying again").isTrue();
        assertChainValid(admin);
    }

    private void terminate(int pid) {
        try (Connection c = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement s = c.prepareStatement("select pg_terminate_backend(?)")) {
            s.setInt(1, pid);
            try (ResultSet r = s.executeQuery()) {
                assertThat(r.next() && r.getBoolean(1)).as("pg_terminate_backend(%d)", pid).isTrue();
            }
        } catch (SQLException e) {
            throw new AssertionError("Could not end the backend " + pid, e);
        }
    }

    // S10A-AC-20

    @Test
    @DisplayName("S10A-AC-20 sign-ins and an admin action that come while an anonymization recalculates finish "
            + "before it does; then it finishes too, and the chain is one line that the integrity check finds whole")
    void otherEntriesDuringRecalculation() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, company.code());
        Session other = fixtures.agent(admin, company.code());
        ApiResponse answer;
        Company madeMeanwhile;
        boolean doneMeanwhile;

        try (Held held = holdAnonymization(admin, person)) {
            for (int i = 0; i < 3; i++) {
                fixtures.signInAgain(other);
            }
            madeMeanwhile = fixtures.createCompany(fixtures.signInAdmin());
            doneMeanwhile = held.request().isDone();
            held.hold().release();
            answer = Concurrently.finish(held.request(), "the held anonymization");
        }

        assertThat(doneMeanwhile).as("the anonymization had finished while the others ran").isFalse();
        answer.expectStatus(200);
        assertThat(entries(admin, "targetCode", madeMeanwhile.code(), "action", "COMPANY_CREATED"))
                .as("the entry of the company made meanwhile").hasSize(1);
        List<JsonNode> line = chain(admin);
        for (int i = 1; i < line.size(); i++) {
            JsonNode previous = line.get(i - 1);
            JsonNode entry = line.get(i);
            assertThat(number(entry, "seq")).as("seq after %s", previous).isEqualTo(number(previous, "seq") + 1);
            assertThat(text(entry, "prevHash")).as("prevHash of %s", entry).isEqualTo(text(previous, "hash"));
            assertThat(text(entry, "hash")).as("hash of %s", entry).isEqualTo(hash(text(entry, "prevHash"), entry));
        }
        assertChainValid(admin);
    }

    // S10A-AC-21

    @Test
    @DisplayName("S10A-AC-21 while one anonymization runs, another is 409 ANONYMIZATION_RUNNING and changes nothing; "
            + "the first then finishes")
    void oneAtATime() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session first = fixtures.customer(admin, company.code());
        Session second = fixtures.customer(admin, company.code());
        List<JsonNode> secondBefore = entriesOf(admin, second.accountId());
        ApiResponse ofSecond;
        ApiResponse ofFirst;

        try (Held held = holdAnonymization(admin, first)) {
            ofSecond = anonymizeRequest(fixtures.signInAdmin(), second.accountId()).send();
            held.hold().release();
            ofFirst = Concurrently.finish(held.request(), "the held anonymization");
        }

        assertRefused(ofSecond, 409, "ANONYMIZATION_RUNNING");
        ofFirst.expectStatus(200);
        assertUntouched(admin, second, secondBefore);
        assertThat(second.client().get("/api/portal/me").send().status()).as("the second customer's session")
                .isEqualTo(200);
    }

    // S10A-AC-22

    @Test
    @DisplayName("S10A-AC-22 when the retention job comes due while an anonymization recalculates, both finish as "
            + "in one order: the entry over the period is gone, the person's entries are anonymous, and the "
            + "integrity check says all entries match")
    void retentionDuringRecalculation() {
        Session before = fixtures.signInAdmin();
        Company old = fixtures.createCompany(before);
        clock.advance(Duration.ofSeconds(2));
        Company company = fixtures.createCompany(before);
        Session person = fixtures.customer(before, company.code());
        fixtures.signInAgain(person);
        ApiResponse answer;
        Instant due;

        try (Held held = holdAnonymization(before, person)) {
            due = clock.advance(RETENTION.minusSeconds(1));
            held.hold().release();
            answer = Concurrently.finish(held.request(), "the held anonymization");
        }
        awaitRetentionRun(due);

        answer.expectStatus(200);
        Session admin = fixtures.signInAdmin();
        assertThat(entries(admin, "targetCode", old.code(), "action", "COMPANY_CREATED"))
                .as("the entry over the retention period").isEmpty();
        List<JsonNode> ofPerson = entriesOf(admin, person.accountId());
        assertThat(ofPerson).extracting(e -> e.path("action").asString("")).as("kinds of the person's entries")
                .contains("INVITATION_CREATED", "ACCOUNT_ANONYMIZED");
        assertThat(ofPerson).allSatisfy(e -> assertThat(chainJson(e)).as("entry %s", e)
                .doesNotContain(person.email()).doesNotContain(person.name()));
        assertThat(ofPerson.stream().filter(e -> e.path("actor").path("accountId").asLong(-1) == person.accountId())
                .map(e -> e.path("actor").path("email").asString(null)).distinct().toList())
                .as("actor emails of the person's own entries").containsOnly(anonymousEmail(person.accountId()));
        assertChainValid(admin);
    }

    /** Waits for the row of the {@code audit-retention} job in the cleanup record (V3) to show a run at or after due. */
    private void awaitRetentionRun(Instant due) {
        Wait.until("the audit-retention job run at or after " + due, Duration.ofSeconds(60), () -> {
            List<Map<String, Object>> rows = db.rows("select last_run_at from cleanup_run where job = ?",
                    "audit-retention");
            if (rows.size() != 1) {
                return null;
            }
            Object at = rows.getFirst().get("last_run_at");
            Instant ran = at instanceof Timestamp t ? t.toInstant()
                    : at instanceof OffsetDateTime o ? o.toInstant() : null;
            return ran != null && !ran.isBefore(due) ? ran : null;
        });
    }
}
