package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.instant;
import static io.popdesk.audit.AuditSteps.number;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.AuditSteps.text;
import static io.popdesk.audit.ChainSteps.assertBroken;
import static io.popdesk.audit.ChainSteps.assertValid;
import static io.popdesk.audit.ChainSteps.verify;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.audit.ChainSteps.Tampering;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The integrity check against changes made in the database behind the app's back (TM-29): a changed
 * value, a deleted entry in the middle, a deleted last entry, and a second check while one runs.
 *
 * <p>These tests break the chain, so they run in a context of their own (an app and a database no
 * other test class but {@link AuditRetentionTest} uses; the property below only gives the context
 * another key). Each test first checks that the chain is whole, breaks it, and puts the rows back
 * as they were, so no test depends on another.
 */
@TestPropertySource(properties = AuditTamperTest.OWN_CONTEXT)
class AuditTamperTest extends BareAppTest {

    /** A property the app does not read; it makes the context of these tests one of their own. */
    static final String OWN_CONTEXT = "audit.test.context=own";

    /** A value of an entry changed in its row; each is one of the values the hash covers. */
    enum Changed {
        KIND("action = 'COMPANY_UPDATED'"),
        PERSON("actor = jsonb_set(actor, '{name}', '\"Somebody Else\"')"),
        TARGET("target = jsonb_set(target, '{code}', '\"ZZZZ9\"')"),
        CHANGES("changes = jsonb_set(changes, '{name}', '\"Another name\"')"),
        TIME("occurred_at = occurred_at + interval '1 second'");

        private final String assignment;

        Changed(String assignment) {
            this.assignment = assignment;
        }
    }

    /** An admin and the entries of three companies the admin created one after the other. */
    private record Three(Session admin, JsonNode first, JsonNode middle, JsonNode last) {}

    @Autowired
    PostgreSQLContainer postgres;

    private Tampering tampering() {
        return new Tampering(db, postgres);
    }

    /** Three entries in a row at the end of a chain the integrity check finds whole. */
    private Three threeEntries() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Company c = fixtures.createCompany(admin);
        JsonNode first = onlyEntry(admin, "targetCode", a.code(), "action", "COMPANY_CREATED");
        JsonNode middle = onlyEntry(admin, "targetCode", b.code(), "action", "COMPANY_CREATED");
        JsonNode last = onlyEntry(admin, "targetCode", c.code(), "action", "COMPANY_CREATED");
        assertThat(number(middle, "seq")).as("seq of the second company's entry").isEqualTo(number(first, "seq") + 1);
        assertThat(number(last, "seq")).as("seq of the third company's entry").isEqualTo(number(middle, "seq") + 1);
        assertValid(admin);
        return new Three(admin, first, middle, last);
    }

    private static void assertBrokenEntry(JsonNode answer, long id, String action, Instant occurredAt) {
        JsonNode broken = answer.path("brokenEntry");
        assertThat(broken.isObject()).as("brokenEntry in %s", answer).isTrue();
        assertThat(number(broken, "id")).as("id of the broken entry in %s", answer).isEqualTo(id);
        assertThat(text(broken, "action")).as("action of the broken entry in %s", answer).isEqualTo(action);
        assertThat(instant(text(broken, "occurredAt"))).as("time of the broken entry in %s", answer)
                .isEqualTo(occurredAt);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Changed.class)
    @DisplayName("S1B-AC-15 after a value of an entry was changed in the database, the integrity check answers "
            + "ENTRY_MISMATCH with that entry as the broken one")
    void changedValueIsFound(Changed changed) {
        Three three = threeEntries();
        long id = number(three.middle(), "id");
        Tampering tampering = tampering();
        String row = tampering.row(id);
        JsonNode answer;
        try {
            tampering.change(id, changed.assignment);

            answer = assertBroken(three.admin(), "ENTRY_MISMATCH");
        } finally {
            tampering.putBack(row);
        }

        assertBrokenEntry(answer, id, changed == Changed.KIND ? "COMPANY_UPDATED" : "COMPANY_CREATED",
                changed == Changed.TIME ? AuditSteps.time(three.middle()).plusSeconds(1)
                        : AuditSteps.time(three.middle()));
    }

    @Test
    @DisplayName("S1B-AC-14 with two entries changed, the integrity check gives the first broken one: its ID, "
            + "its time, and its kind")
    void firstBrokenEntryIsGiven() {
        Three three = threeEntries();
        Tampering tampering = tampering();
        String first = tampering.row(number(three.first(), "id"));
        String last = tampering.row(number(three.last(), "id"));
        JsonNode answer;
        try {
            tampering.change(number(three.last(), "id"), Changed.CHANGES.assignment);
            tampering.change(number(three.first(), "id"), Changed.CHANGES.assignment);

            answer = assertBroken(three.admin(), "ENTRY_MISMATCH");
        } finally {
            tampering.putBack(first);
            tampering.putBack(last);
        }

        assertBrokenEntry(answer, number(three.first(), "id"), "COMPANY_CREATED", AuditSteps.time(three.first()));
    }

    @Test
    @DisplayName("S1B-AC-16 after an entry in the middle was deleted in the database, the integrity check answers "
            + "ENTRY_MISMATCH with the entry after it as the broken one")
    void deletedMiddleEntryIsFound() {
        Three three = threeEntries();
        long id = number(three.middle(), "id");
        Tampering tampering = tampering();
        String row = tampering.row(id);
        JsonNode answer;
        try {
            tampering.delete(id);

            answer = assertBroken(three.admin(), "ENTRY_MISMATCH");
        } finally {
            tampering.putBack(row);
        }

        assertBrokenEntry(answer, number(three.last(), "id"), "COMPANY_CREATED", AuditSteps.time(three.last()));
    }

    @Test
    @DisplayName("S1B-AC-42 after the last entry was deleted in the database, the integrity check answers "
            + "HEAD_MISMATCH with no broken entry")
    void deletedLastEntryIsFound() {
        Three three = threeEntries();
        long id = number(three.last(), "id");
        assertThat(entries(three.admin()).stream().mapToLong(entry -> number(entry, "seq")).max().orElse(-1))
                .as("the highest seq in the log: the third company's entry is the last of the chain")
                .isEqualTo(number(three.last(), "seq"));
        Tampering tampering = tampering();
        String row = tampering.row(id);
        JsonNode answer;
        try {
            tampering.delete(id);

            answer = assertBroken(three.admin(), "HEAD_MISMATCH");
        } finally {
            tampering.putBack(row);
        }

        assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
    }

    private static ApiResponse ended(Future<ApiResponse> request) {
        try {
            return request.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("A held integrity check did not end with a response: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for a held integrity check", e);
        }
    }

    @Test
    @DisplayName("S1B-AC-18 while an integrity check runs, a second one is 409 AUDIT_VERIFICATION_RUNNING; the "
            + "first still ends valid, and a check after it runs again")
    void secondCheckWhileOneRunsIsRefused() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Session secondBrowser = fixtures.signInAgain(admin);
        assertValid(admin);
        Tampering tampering = tampering();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        Connection lock = tampering.lockAgainstReaders();
        boolean held = true;
        try {
            Future<ApiResponse> running = thread.submit(() -> verify(admin));
            Wait.until("an integrity check waiting to read the audit table", () -> {
                if (running.isDone()) {
                    throw new AssertionError("The integrity check ended while nobody could read the audit table: "
                            + "status " + ended(running).status());
                }
                return tampering.auditStatementWaitsForALock() ? Boolean.TRUE : null;
            });

            ApiResponse refused = verify(secondBrowser);

            Tampering.letGo(lock);
            held = false;
            ApiResponse first = ended(running);
            assertThat(refused.status()).as("%s: %s", refused, refused.snippet()).isEqualTo(409);
            assertThat(refused.errorCode()).isEqualTo("AUDIT_VERIFICATION_RUNNING");
            assertThat(first.status()).as("the check that was running: %s", first.snippet()).isEqualTo(200);
            assertThat(first.json().path("valid").asBoolean(false)).as("valid in %s", first.snippet()).isTrue();
            assertValid(secondBrowser);
        } finally {
            if (held) {
                Tampering.letGo(lock);
            }
            thread.shutdownNow();
        }
    }
}
