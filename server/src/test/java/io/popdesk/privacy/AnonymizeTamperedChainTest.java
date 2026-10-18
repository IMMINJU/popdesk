package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.support.AuditChainMath.chainJson;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * An anonymization meets a chain someone changed in the database behind the app's back (S10A-AC-37, acceptance.md
 * 5절 11): a break in the range it would compute again is refused with 409 {@code AUDIT_CHAIN_BROKEN} and changes
 * nothing; a break before that range does not stop it, and the integrity check still finds the break.
 *
 * <p>The tests break the chain, so they run in a context of their own ({@link OwnContext}, as in
 * {@link LastAdminAnonymizeTest}), not in {@link PrivacyAppTest}. Each test makes its own entries, checks that the chain
 * is whole, changes one value with SQL (the one exception to "HTTP only", as for schema tests), and puts it back. When
 * an anonymization went further than it should (before the fix, always), the audit rows from B on are put back whole
 * ({@link #restore}), so no test depends on another. While a value is changed nothing appends to the chain but what
 * the test looks at: no sign-in, no audit export.
 *
 * <p>Entries of one test, in chain order: B (company created), then the customer's entries from P (the invitation), then
 * M and A (two more companies). B is before the range an anonymization of the customer computes again; M is inside it
 * and not the customer's; A keeps M from being the last entry.
 */
@Import(AnonymizeTamperedChainTest.OwnContext.class)
class AnonymizeTamperedChainTest extends BareAppTest {

    /** Nothing in it; importing it gives this class a test context no other class shares. */
    @TestConfiguration(proxyBeanMethods = false)
    static class OwnContext {}

    private static final String VERIFY = PrivacySteps.AUDIT_LOG + "/verify";

    /** A name no entry holds; written into a changed entry's values. */
    private static final String TAMPERED_NAME = "Tampered-name-5e8a";

    /** A previous hash or head hash no entry has. */
    private static final String TAMPERED_HASH = "0123456789abcdef".repeat(4);

    /** What is changed in the range an anonymization computes again (from P to the head). */
    enum InRange {
        /** A value of M; its hash stays. */
        VALUE,
        /** The previous hash of M: it no longer follows the entry before. */
        PREV_HASH,
        /** The head of the chain ({@code audit_chain.head_hash}): it no longer matches the last entry. */
        HEAD
    }

    /** The entries of one test and the customer whose entries start after B. */
    private record World(Session admin, Session person, JsonNode before, JsonNode middle, JsonNode last) {}

    /** The value a change of an audit row replaced, and the SQL that writes it back. */
    private record Undo(String sql, long id, String value) {}

    /** The audit rows from {@code fromSeq} on, each as JSON text, and the head of the chain. */
    private record Snapshot(long fromSeq, List<String> rows, long headSeq, String headHash) {}

    @Autowired
    PostgreSQLContainer postgres;

    private World world() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company before = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, before.code());
        Company middle = fixtures.createCompany(admin);
        fixtures.createCompany(admin);
        JsonNode b = companyCreated(admin, before);
        JsonNode m = companyCreated(admin, middle);
        JsonNode last = PrivacySteps.newest(admin);
        long first = firstSeqOf(admin, person);
        assertThat(number(b, "seq")).as("seq of B, before the customer's first entry %d", first).isLessThan(first);
        assertThat(number(m, "seq")).as("seq of M, after the customer's first entry %d", first).isGreaterThan(first);
        assertThat(number(last, "seq")).as("seq of the last entry, after M").isGreaterThan(number(m, "seq"));
        PrivacySteps.assertChainValid(admin);
        return new World(admin, person, b, m, last);
    }

    private static JsonNode companyCreated(Session admin, Company company) {
        List<JsonNode> found = PrivacySteps.entries(admin, "targetCode", company.code(), "action", "COMPANY_CREATED");
        assertThat(found).as("COMPANY_CREATED entries of %s", company.code()).hasSize(1);
        return found.getFirst();
    }

    private static long firstSeqOf(Session admin, Session person) {
        List<JsonNode> entries = PrivacySteps.entriesOf(admin, person.accountId());
        assertThat(entries).as("entries of account %d", person.accountId()).isNotEmpty();
        return number(entries.getFirst(), "seq");
    }

    // Changing the database

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        connection.setSchema(db.schema());
        return connection;
    }

    /** Runs an UPDATE that must change exactly one row. */
    private void update(String sql, Object... args) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            int changed = statement.executeUpdate();
            if (changed != 1) {
                throw new AssertionError(sql + " changed " + changed + " rows, not 1");
            }
        } catch (SQLException e) {
            throw new AssertionError("Could not run: " + sql, e);
        }
    }

    private static String value(Map<String, Object> row) {
        Object value = row.get("v");
        if (value == null) {
            throw new AssertionError("No value in " + row);
        }
        return value.toString();
    }

    /** Sets {@code changes.name} of an audit row to {@link #TAMPERED_NAME}; its hash stays. */
    private Undo changeName(JsonNode entry) {
        long id = number(entry, "id");
        String old = value(db.row("select changes::text as v from audit_log where id = ?", id));
        update("update audit_log set changes = jsonb_set(changes, '{name}', to_jsonb(cast(? as text))) where id = ?",
                TAMPERED_NAME, id);
        return new Undo("update audit_log set changes = cast(? as jsonb) where id = ?", id, old);
    }

    /** Sets the previous hash of an audit row to {@link #TAMPERED_HASH}; its hash stays. */
    private void changePrevHash(JsonNode entry) {
        update("update audit_log set prev_hash = ? where id = ?", TAMPERED_HASH, number(entry, "id"));
    }

    /** Sets the head hash of the chain to {@link #TAMPERED_HASH}. */
    private void changeHead() {
        update("update audit_chain set head_hash = ?", TAMPERED_HASH);
    }

    private void putBack(Undo undo) {
        update(undo.sql(), undo.value(), undo.id());
    }

    /** The audit rows from B on and the head, whole, as they are now. */
    private Snapshot snapshot(World world) {
        long from = number(world.before(), "seq");
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> row : db.rows(
                "select row_to_json(t)::text as v from audit_log t where seq >= ? order by seq", from)) {
            rows.add(value(row));
        }
        Map<String, Object> head = db.row("select head_seq, head_hash from audit_chain");
        return new Snapshot(from, rows, ((Number) head.get("head_seq")).longValue(), head.get("head_hash").toString());
    }

    /**
     * Puts the audit rows from B on and the head back as {@link #snapshot} read them, whatever an anonymization or a
     * change did since, so a failed test leaves no break for the next one. The account rows are not put back; no other
     * test reads them.
     */
    private void restore(Snapshot snapshot) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("delete from audit_log where seq >= ?");
                    PreparedStatement insert = connection.prepareStatement("insert into audit_log "
                            + "select * from jsonb_populate_record(null::audit_log, cast(? as jsonb))");
                    PreparedStatement head = connection.prepareStatement(
                            "update audit_chain set head_seq = ?, head_hash = ?")) {
                delete.setLong(1, snapshot.fromSeq());
                delete.executeUpdate();
                for (String row : snapshot.rows()) {
                    insert.setString(1, row);
                    insert.executeUpdate();
                }
                head.setLong(1, snapshot.headSeq());
                head.setString(2, snapshot.headHash());
                head.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new AssertionError("Could not put the audit rows from seq " + snapshot.fromSeq() + " back", e);
        }
    }

    // The integrity check

    /** Runs the integrity check and expects 200 with {@code valid: false} and this reason. */
    private static JsonNode assertBroken(Session admin, String reason) {
        JsonNode answer = admin.client().post(VERIFY).send().expectStatus(200).json();
        assertThat(answer.path("valid").isBoolean() && !answer.path("valid").booleanValue())
                .as("valid is false in the integrity check's answer %s", answer).isTrue();
        assertThat(answer.path("reason").asString(null)).as("reason in %s", answer).isEqualTo(reason);
        return answer;
    }

    private static void assertBrokenAt(Session admin, InRange changed, World world) {
        if (changed == InRange.HEAD) {
            JsonNode answer = assertBroken(admin, "HEAD_MISMATCH");
            assertThat(answer.path("brokenEntry").isNull()).as("brokenEntry is null in %s", answer).isTrue();
        } else {
            assertBrokenEntry(assertBroken(admin, "ENTRY_MISMATCH"), world.middle());
        }
    }

    private static void assertBrokenEntry(JsonNode answer, JsonNode entry) {
        assertThat(answer.path("brokenEntry").path("id").asLong(-1)).as("id of the broken entry in %s", answer)
                .isEqualTo(number(entry, "id"));
    }

    // What a refused anonymization leaves

    /** The account's name, email, status, and {@code anonymized}. */
    private static List<Object> accountState(Session admin, Session person) {
        JsonNode account = PrivacySteps.account(admin, person.accountId(), person.email());
        return List.of(account.path("name").asString(""), account.path("email").asString(""),
                account.path("status").asString(""), account.path("anonymized").asBoolean(true));
    }

    /** Each entry of the person as its ID, chain text, previous hash, and hash. */
    private static List<String> entryState(Session admin, Session person) {
        List<String> out = new ArrayList<>();
        for (JsonNode entry : PrivacySteps.entriesOf(admin, person.accountId())) {
            out.add(number(entry, "id") + " " + chainJson(entry) + " " + text(entry, "prevHash") + " "
                    + text(entry, "hash"));
        }
        return out;
    }

    /** Every whole number in a JSON value, at any depth. */
    private static List<Long> numbersIn(JsonNode node) {
        List<Long> out = new ArrayList<>();
        if (node.isIntegralNumber()) {
            out.add(node.asLong());
        }
        for (JsonNode child : node) {
            out.addAll(numbersIn(child));
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InRange.class)
    @DisplayName("S10A-AC-37 when the range an anonymization computes again holds an entry whose value changed, an "
            + "entry that no longer follows the one before, or a head that does not match the last entry, the "
            + "anonymization is 409 AUDIT_CHAIN_BROKEN with the first broken seq and no value, the account and its "
            + "entries stay as they were, and the integrity check finds the break at the same place")
    void breakInTheRangeIsRefused(InRange changed) {
        World world = world();
        Session admin = world.admin();
        Session person = world.person();
        List<Object> accountBefore = accountState(admin, person);
        List<String> entriesBefore = entryState(admin, person);
        long firstSeq = firstSeqOf(admin, person);
        long brokenSeq = number(changed == InRange.HEAD ? world.last() : world.middle(), "seq");
        Snapshot snapshot = snapshot(world);
        ApiResponse refused;
        List<Object> accountAfter;
        List<String> entriesAfter;
        JsonNode newestAfter;
        List<JsonNode> rechained;
        try {
            switch (changed) {
                case VALUE -> changeName(world.middle());
                case PREV_HASH -> changePrevHash(world.middle());
                case HEAD -> changeHead();
            }
            refused = PrivacySteps.anonymizeRequest(admin, person.accountId()).send();
            assertRefused(refused, 409, "AUDIT_CHAIN_BROKEN");

            accountAfter = accountState(admin, person);
            entriesAfter = entryState(admin, person);
            newestAfter = PrivacySteps.newest(admin);
            rechained = PrivacySteps.entries(admin, "action", "AUDIT_RECHAINED");
            assertBrokenAt(admin, changed, world);
        } finally {
            restore(snapshot);
        }

        assertThat(numbersIn(refused.json())).as("whole numbers in the refusal %s: the first broken seq",
                refused.snippet()).contains(brokenSeq);
        assertThat(refused.text()).as("the refusal holds no value of an entry or the head")
                .doesNotContain(TAMPERED_NAME).doesNotContainPattern("[0-9a-f]{64}");
        assertThat(accountAfter).as("name, email, status, anonymized of account %d after the refusal",
                person.accountId()).isEqualTo(accountBefore).containsExactly(person.name(), person.email(), "ACTIVE",
                false);
        assertThat(entriesAfter).as("entries of account %d after the refusal", person.accountId())
                .isEqualTo(entriesBefore);
        assertThat(number(newestAfter, "id")).as("the newest entry after the refusal: nothing was appended")
                .isEqualTo(number(world.last(), "id"));
        assertThat(rechained).as("AUDIT_RECHAINED entries from seq %d", firstSeq)
                .noneMatch(e -> e.path("changes").path("fromSeq").asLong(-1) == firstSeq);
        PrivacySteps.assertChainValid(admin);
    }

    @Test
    @DisplayName("S10A-AC-37 when only an entry before the person's first entry changed, the anonymization is 200 and "
            + "does not compute that entry again: the integrity check still finds the break at it, and with its value "
            + "put back the chain is whole")
    void breakBeforeTheRangeIsLeft() {
        World world = world();
        Session admin = world.admin();
        Session person = world.person();
        long firstSeq = firstSeqOf(admin, person);
        Snapshot snapshot = snapshot(world);
        Undo undo = changeName(world.before());
        JsonNode answer;
        JsonNode whole;
        try {
            PrivacySteps.anonymize(admin, person.accountId());

            answer = assertBroken(admin, "ENTRY_MISMATCH");
        } finally {
            putBack(undo);
        }
        whole = admin.client().post(VERIFY).send().expectStatus(200).json();
        if (!whole.path("valid").asBoolean(false)) {
            restore(snapshot);
        }

        assertBrokenEntry(answer, world.before());
        assertThat(whole.path("valid").asBoolean(false))
                .as("the integrity check after B was put back: the anonymization computed nothing before the "
                        + "customer's first entry; %s", whole).isTrue();
        JsonNode gone = PrivacySteps.anonymizedAccount(admin, person.accountId());
        assertThat(gone.path("anonymized").asBoolean(false)).as("account %d is anonymized", person.accountId())
                .isTrue();
        assertThat(PrivacySteps.entries(admin, "action", "AUDIT_RECHAINED"))
                .as("AUDIT_RECHAINED entries from the customer's first entry, seq %d", firstSeq)
                .anyMatch(e -> e.path("changes").path("fromSeq").asLong(-1) == firstSeq);
    }
}
