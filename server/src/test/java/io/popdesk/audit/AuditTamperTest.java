package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.ended;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.instant;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.ChainSteps.assertBroken;
import static io.popdesk.audit.ChainSteps.awaitRetentionRun;
import static io.popdesk.audit.ChainSteps.chain;
import static io.popdesk.audit.ChainSteps.verify;
import static io.popdesk.support.AuditChainMath.assertValid;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.audit.ChainSteps.Tampering;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The integrity check against changes made in the database behind the app's back (TM-29): a changed
 * value, with and without the hash that fits it, a deleted entry in the middle, a deleted last entry,
 * the same at the front of a chain the retention job shortened, and a second check while one runs.
 *
 * <p>These tests break the chain, so they run in a context of their own (an app and a database no
 * other test class but {@link AuditRetentionTest} uses; the property below only gives the context
 * another key). Each test first checks that the chain is whole, breaks it, and puts the rows back
 * as they were, so no test depends on another.
 *
 * <p>The tests name the columns the audit table had before this slice. The one exception is
 * {@code hash}, the name design section 1 gives the column: a change that also writes the hash
 * fitting the changed row cannot be made without it.
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

    /** At the front of a chain the retention job shortened: what is done to the first entry left. */
    enum AtTheFront {
        DELETED,
        CHANGED
    }

    /** An admin and the first two entries left after the retention job deleted the ones before them. */
    private record Front(Session admin, JsonNode first, JsonNode second) {}

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
        assertValid(admin.client());
        return new Three(admin, first, middle, last);
    }

    /** Fails unless the third company's entry is the last of the chain. */
    private static void assertLastOfTheChain(Three three) {
        assertThat(entries(three.admin()).stream().mapToLong(entry -> number(entry, "seq")).max().orElse(-1))
                .as("the highest seq in the log: the third company's entry is the last of the chain")
                .isEqualTo(number(three.last(), "seq"));
    }

    /**
     * Changes the name in an entry's changed values and writes the hash that fits the changed row into
     * its {@code hash} column, as someone who knows the rule of the chain would. The row's previous
     * hash stays, so the changed row is right by itself.
     */
    private static void changeAndHashAgain(Tampering tampering, JsonNode entry) {
        ObjectNode changed = (ObjectNode) entry.deepCopy();
        ((ObjectNode) changed.get("changes")).put("name", "Another name");
        String hash = AuditChainMath.hash(text(entry, "prevHash"), changed);
        tampering.change(number(entry, "id"), Changed.CHANGES.assignment + ", hash = '" + hash + "'");
    }

    /**
     * Lets the retention job delete the front of the chain once: an entry 1 second over the period
     * and everything before it. The first entry left is a company created 1 second inside the period;
     * a sign-in and another company follow it.
     */
    private Front afterRetention() {
        Session before = fixtures.admin(fixtures.signInAdmin());
        fixtures.createCompany(before);
        clock.advance(Duration.ofSeconds(2));
        JsonNode kept = onlyEntry(before, "targetCode", fixtures.createCompany(before).code(), "action",
                "COMPANY_CREATED");
        awaitRetentionRun(db, clock.advance(AuditRetentionTest.PERIOD.minusSeconds(1)));
        Session admin = fixtures.signInAdmin();
        fixtures.createCompany(admin);
        List<JsonNode> chain = chain(admin);
        assertThat(chain).as("entries left after the retention job ran").hasSizeGreaterThanOrEqualTo(2);
        assertThat(number(chain.getFirst(), "id"))
                .as("id of the first entry left: the company created inside the period").isEqualTo(number(kept, "id"));
        assertThat(number(chain.getFirst(), "seq")).as("seq of the first entry left; the ones before were deleted")
                .isGreaterThan(1);
        assertValid(admin.client());
        return new Front(admin, chain.get(0), chain.get(1));
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
    @DisplayName("S1B-AC-15, S1B-AC-24 after a value of an entry was changed in the database, the integrity check "
            + "answers ENTRY_MISMATCH with that entry as the broken one, and a JSON Lines export still gives the "
            + "hash stored for it, not one computed again from the changed values")
    void changedValueIsFound(Changed changed) {
        Three three = threeEntries();
        long id = number(three.middle(), "id");
        Tampering tampering = tampering();
        String row = tampering.row(id);
        JsonNode answer;
        List<JsonNode> exported;
        try {
            tampering.change(id, changed.assignment);

            answer = assertBroken(three.admin(), "ENTRY_MISMATCH");
            exported = chain(three.admin()).stream().filter(line -> number(line, "id") == id).toList();
        } finally {
            tampering.putBack(row);
        }

        assertThat(exported).as("lines of the changed entry in an export made while it was changed").hasSize(1);
        assertThat(text(exported.getFirst(), "hash")).as("hash of the changed entry's line: the stored one")
                .isEqualTo(text(three.middle(), "hash"));
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
        assertLastOfTheChain(three);
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

    @Test
    @DisplayName("S1B-AC-14, S1B-AC-15, S1B-AC-16 after a value of a middle entry was changed and the hash that "
            + "fits the changed row was written with it, the integrity check answers ENTRY_MISMATCH with the entry "
            + "after it as the broken one: its previous hash is no longer the hash of the entry before")
    void changedMiddleEntryWithAFittingHashBreaksTheNextEntry() {
        Three three = threeEntries();
        Tampering tampering = tampering();
        String row = tampering.row(number(three.middle(), "id"));
        JsonNode answer;
        try {
            changeAndHashAgain(tampering, three.middle());

            answer = assertBroken(three.admin(), "ENTRY_MISMATCH");
        } finally {
            tampering.putBack(row);
        }

        assertBrokenEntry(answer, number(three.last(), "id"), "COMPANY_CREATED", AuditSteps.time(three.last()));
    }

    @Test
    @DisplayName("S1B-AC-42 after a value of the last entry was changed and the hash that fits the changed row was "
            + "written with it, the integrity check answers HEAD_MISMATCH with no broken entry")
    void changedLastEntryWithAFittingHashIsFoundAtTheHead() {
        Three three = threeEntries();
        assertLastOfTheChain(three);
        Tampering tampering = tampering();
        String row = tampering.row(number(three.last(), "id"));
        JsonNode answer;
        try {
            changeAndHashAgain(tampering, three.last());

            answer = assertBroken(three.admin(), "HEAD_MISMATCH");
        } finally {
            tampering.putBack(row);
        }

        assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AtTheFront.class)
    @DisplayName("S1B-AC-17, S1B-AC-21 the check starts from the point the retention job left, so the first entry "
            + "left is checked too: deleted, the entry after it is the broken one; a value of it changed, it is "
            + "the broken one itself")
    void firstEntryLeftAfterRetentionIsChecked(AtTheFront done) {
        Front front = afterRetention();
        long id = number(front.first(), "id");
        Tampering tampering = tampering();
        String row = tampering.row(id);
        JsonNode answer;
        try {
            if (done == AtTheFront.DELETED) {
                tampering.delete(id);
            } else {
                tampering.change(id, Changed.CHANGES.assignment);
            }

            answer = assertBroken(front.admin(), "ENTRY_MISMATCH");
        } finally {
            tampering.putBack(row);
        }

        JsonNode broken = done == AtTheFront.DELETED ? front.second() : front.first();
        assertBrokenEntry(answer, number(broken, "id"), text(broken, "action"), AuditSteps.time(broken));
    }

    @Test
    @DisplayName("S1B-AC-18 while an integrity check runs, a second one is 409 AUDIT_VERIFICATION_RUNNING; the "
            + "first still ends valid, and a check after it runs again")
    void secondCheckWhileOneRunsIsRefused() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Session secondBrowser = fixtures.signInAgain(admin);
        assertValid(admin.client());
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
            assertValid(secondBrowser.client());
        } finally {
            if (held) {
                Tampering.letGo(lock);
            }
            thread.shutdownNow();
        }
    }
}
