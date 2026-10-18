package io.popdesk.privacy;

import static io.popdesk.privacy.PrivacySteps.anonymize;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static io.popdesk.privacy.PrivacySteps.assertChainValid;
import static io.popdesk.privacy.PrivacySteps.copies;
import static io.popdesk.privacy.PrivacySteps.entries;
import static io.popdesk.privacy.PrivacySteps.entriesOf;
import static io.popdesk.support.AuditChainMath.HASH_PATTERN;
import static io.popdesk.support.AuditChainMath.chainJson;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.support.WebhookReceiver.Reply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The person's audit entries after anonymizing (PII-5, AUDIT-6): the person fields hold the anonymous values, the
 * chain is computed again and says so in {@code AUDIT_RECHAINED}, an entry whose values were made before the commit
 * is appended with the anonymous values, and the outside copies not sent yet go out with the new values.
 *
 * <p>The person fields are {@code actor.name}, {@code actor.email}, and for an entry whose target is the account the
 * top-level {@code name} and {@code email} of {@code changes}, text or {@code {from, to}} (acceptance.md "말의 뜻").
 * {@link #anonymized} writes that rule out for the few entries here; the full rule is the unit test of
 * {@code AuditAnonymizer} after C-5.
 */
class AnonymizeAuditTest extends PrivacyAppTest {

    /** The entry as it should read after anonymizing {@code accountId}: person fields replaced, the rest kept. */
    private static JsonNode anonymized(JsonNode entry, long accountId) {
        ObjectNode out = (ObjectNode) entry.deepCopy();
        if (entry.path("actor").path("accountId").asLong(-1) == accountId) {
            ((ObjectNode) out.get("actor")).put("name", ANONYMOUS_NAME).put("email", anonymousEmail(accountId));
        }
        if (entry.path("target").path("accountId").asLong(-1) == accountId && entry.path("changes").isObject()) {
            ObjectNode changes = (ObjectNode) out.get("changes");
            replace(changes, "name", ANONYMOUS_NAME);
            replace(changes, "email", anonymousEmail(accountId));
        }
        return out;
    }

    private static void replace(ObjectNode changes, String field, String value) {
        JsonNode old = changes.get(field);
        if (old == null || old.isNull()) {
            return;
        }
        if (old.isObject()) {
            ObjectNode pair = (ObjectNode) old;
            if (pair.has("from")) {
                pair.put("from", value);
            }
            if (pair.has("to")) {
                pair.put("to", value);
            }
        } else {
            changes.put(field, value);
        }
    }

    /** The entries by ID. */
    private static Map<Long, JsonNode> byId(List<JsonNode> entries) {
        Map<Long, JsonNode> out = new HashMap<>();
        entries.forEach(e -> out.put(number(e, "id"), e));
        return out;
    }

    /** Fails unless no person field of an entry of the account holds anything but the anonymous values. */
    private static void assertAllAnonymous(List<JsonNode> entries, long accountId, String oldName, String oldEmail) {
        assertThat(entries).as("entries of account %d", accountId).isNotEmpty();
        for (JsonNode entry : entries) {
            assertThat(chainJson(entry)).as("entry %s", entry).isEqualTo(chainJson(anonymized(entry, accountId)));
            if (entry.path("actor").path("accountId").asLong(-1) == accountId
                    || entry.path("target").path("accountId").asLong(-1) == accountId) {
                assertThat(chainJson(entry)).as("person fields of %s", entry).doesNotContain(oldEmail)
                        .doesNotContain(oldName);
            }
        }
    }

    // S10A-AC-17

    @Test
    @DisplayName("S10A-AC-17 anonymizing replaces the person fields of the person's entries (actor of a company "
            + "created, target of the invitation and of a rename with name and email in changes) and nothing else; "
            + "the entries of another account with the same name stay as they were")
    void personFieldsReplaced() {
        Session first = fixtures.signInAdmin();
        String name = Unique.text("Same name");
        Session person = fixtures.accept(fixtures.invite(first, Role.ADMIN, Unique.email("person"), name, List.of()));
        Session namesake = fixtures.accept(fixtures.invite(first, Role.AGENT, Unique.email("namesake"), name,
                List.of()));
        fixtures.signInAgain(person);
        Company made = fixtures.createCompany(person);
        String renamed = Unique.text("Renamed");
        first.client().patch(PrivacySteps.ACCOUNTS + "/" + person.accountId()).json(Map.of("name", renamed))
                .expect(200);
        List<JsonNode> before = entriesOf(first, person.accountId());
        List<JsonNode> namesakeBefore = entriesOf(first, namesake.accountId());
        assertThat(before).extracting(e -> e.path("action").asString("")).as("kinds of the person's entries")
                .contains("INVITATION_CREATED", "SIGN_IN_SUCCEEDED", "COMPANY_CREATED", "ACCOUNT_NAME_CHANGED");

        anonymize(first, person.accountId());

        Map<Long, JsonNode> after = byId(entriesOf(first, person.accountId()));
        for (JsonNode entry : before) {
            JsonNode now = after.get(number(entry, "id"));
            assertThat(now).as("entry %d after anonymizing", number(entry, "id")).isNotNull();
            assertThat(chainJson(now)).as("entry %s after anonymizing", entry)
                    .isEqualTo(chainJson(anonymized(entry, person.accountId())));
            assertThat(number(now, "seq")).as("seq of %s", entry).isEqualTo(number(entry, "seq"));
        }
        JsonNode ofCompany = after.values().stream()
                .filter(e -> made.code().equals(e.path("target").path("code").asString(null))).findFirst()
                .orElseThrow(() -> new AssertionError("No entry of the company " + made.code()));
        assertThat(text(ofCompany.path("actor"), "name")).as("actor of the company's entry").isEqualTo(ANONYMOUS_NAME);
        Map<Long, JsonNode> namesakeAfter = byId(entriesOf(first, namesake.accountId()));
        for (JsonNode entry : namesakeBefore) {
            assertThat(chainJson(namesakeAfter.get(number(entry, "id")))).as("the namesake's entry %s", entry)
                    .isEqualTo(chainJson(entry));
        }
        assertAllAnonymous(List.copyOf(after.values()), person.accountId(), renamed, person.email());
    }

    // S10A-AC-18, S10A-AC-19

    @Test
    @DisplayName("S10A-AC-18, S10A-AC-19 anonymizing computes the chain again from the person's first entry to the "
            + "head and appends AUDIT_RECHAINED right after it (fromSeq, toSeq, headHash from and to), then "
            + "ACCOUNT_ANONYMIZED; the integrity check says all entries match")
    void rechainedEntry() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, company.code());
        List<JsonNode> before = entriesOf(admin, person.accountId());
        long from = number(before.getFirst(), "seq");
        JsonNode headBefore = PrivacySteps.newest(admin);

        anonymize(admin, person.accountId());

        List<JsonNode> rechained = entries(admin, "action", "AUDIT_RECHAINED").stream()
                .filter(e -> e.path("changes").path("fromSeq").asLong(-1) == from).toList();
        assertThat(rechained).as("AUDIT_RECHAINED entries from seq %d", from).hasSize(1);
        JsonNode entry = rechained.getFirst();
        JsonNode changes = entry.path("changes");
        assertThat(entry.path("target").path("type").asString(null)).as("target type of %s", entry)
                .isEqualTo("AUDIT_LOG");
        assertThat(number(changes, "toSeq")).as("toSeq of %s: the head before anonymizing", entry)
                .isEqualTo(number(headBefore, "seq"));
        assertThat(number(entry, "seq")).as("seq of %s", entry).isEqualTo(number(changes, "toSeq") + 1);
        assertThat(text(changes.path("headHash"), "from")).as("headHash.from: the head's hash before")
                .isEqualTo(text(headBefore, "hash"));
        JsonNode headNow = entries(admin, "from", text(headBefore, "occurredAt")).stream()
                .filter(e -> number(e, "seq") == number(headBefore, "seq")).findFirst()
                .orElseThrow(() -> new AssertionError("No entry with seq " + number(headBefore, "seq")));
        assertThat(text(changes.path("headHash"), "to")).as("headHash.to: the same entry's hash now")
                .isEqualTo(text(headNow, "hash")).isNotEqualTo(text(headBefore, "hash"));
        assertThat(text(entry, "prevHash")).as("prevHash of %s", entry).isEqualTo(text(changes.path("headHash"), "to"));
        JsonNode anonymizedEntry = entries(admin, "targetAccountId", person.accountId(), "action",
                "ACCOUNT_ANONYMIZED").getFirst();
        assertThat(number(anonymizedEntry, "seq")).as("seq of ACCOUNT_ANONYMIZED").isEqualTo(number(entry, "seq") + 1);
        assertChainValid(admin);
    }

    // S10A-AC-35

    @Test
    @DisplayName("S10A-AC-35 a manager's invitation that is held in the database while the manager is anonymized "
            + "ends after the commit, and every entry of the manager, that one too when it was appended, holds the "
            + "anonymous values; an export of the account appended later (actor the admin, target the account) "
            + "holds no old value of the person")
    void lateEntryIsAnonymous() {
        ManagerSteps.Scene scene = ManagerSteps.scene(fixtures, fixtures.signInAdmin());
        Session admin = scene.admin();
        Session manager = scene.manager();
        Session wanted = fixtures.customer(admin, scene.other().code());
        ApiResponse invited;
        try (HeldWrite hold = new HeldWrite(db, postgres, "membership_request", "insert", wanted.email())) {
            CompletableFuture<ApiResponse> inviting = CompletableFuture.supplyAsync(
                    () -> ManagerSteps.invite(manager, scene.code(), wanted.email(), Unique.text("Late")));
            hold.awaitHeld("the manager's membership request");
            anonymize(admin, manager.accountId());
            hold.release();
            invited = Concurrently.finish(inviting, "the held invitation");
        }
        ExportSteps.export(admin, manager.accountId());

        List<JsonNode> entries = entriesOf(admin, manager.accountId());
        if (invited.status() == 204) {
            assertThat(entries).extracting(e -> e.path("action").asString("")).as("kinds of the manager's entries")
                    .contains("MEMBERSHIP_REQUESTED");
        }
        assertThat(entries).extracting(e -> e.path("action").asString("")).as("kinds of the manager's entries")
                .contains("ACCOUNT_ANONYMIZED", "ACCOUNT_EXPORTED");
        List<JsonNode> exported = entries.stream()
                .filter(e -> "ACCOUNT_EXPORTED".equals(e.path("action").asString(null))).toList();
        assertThat(exported).as("ACCOUNT_EXPORTED entries of the manager").hasSize(1);
        JsonNode export = exported.getFirst();
        assertThat(export.path("actor").path("accountId").asLong(-1)).as("actor of %s", export)
                .isEqualTo(admin.accountId());
        assertThat(export.path("target").path("accountId").asLong(-1)).as("target of %s", export)
                .isEqualTo(manager.accountId());
        assertThat(chainJson(export)).as("the export entry %s", export).doesNotContain(manager.email())
                .doesNotContain(manager.name());
        assertAllAnonymous(entries, manager.accountId(), manager.name(), manager.email());
        assertChainValid(admin);
    }

    // S10A-AC-23

    private static boolean isCopyAbout(Received request, long accountId) {
        if (!request.path().equals(COPY_PATH)) {
            return false;
        }
        JsonNode body = request.json();
        return body.path("actor").path("accountId").asLong(-1) == accountId
                || body.path("target").path("accountId").asLong(-1) == accountId;
    }

    @Test
    @DisplayName("S10A-AC-23 with the outside copy on, a copy of the person's entry not sent yet goes out with the "
            + "anonymous values and the new hash, a copy sent before is not sent again, and AUDIT_RECHAINED is "
            + "copied like any entry")
    void outsideCopies() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, company.code());
        long invitation = number(entries(admin, "targetAccountId", person.accountId(), "action",
                "INVITATION_CREATED").getFirst(), "id");
        Wait.until("the copy of the person's invitation entry", () -> copies(webhook()).stream()
                .anyMatch(c -> c.path("id").asLong(-1) == invitation) ? Boolean.TRUE : null);
        webhook().respond(r -> isCopyAbout(r, person.accountId()) ? Reply.status(500) : Reply.status(204));
        String renamed = Unique.text("Renamed");
        admin.client().patch(PrivacySteps.ACCOUNTS + "/" + person.accountId()).json(Map.of("name", renamed))
                .expect(200);
        long rename = number(entries(admin, "targetAccountId", person.accountId(), "action", "ACCOUNT_NAME_CHANGED")
                .getFirst(), "id");
        Wait.until("a refused copy of the rename", () -> copies(webhook()).stream()
                .anyMatch(c -> c.path("id").asLong(-1) == rename) ? Boolean.TRUE : null);
        long from = number(entriesOf(admin, person.accountId()).getFirst(), "seq");

        anonymize(admin, person.accountId());
        int receivedBefore = webhook().requests().size();
        webhook().respondWith(204);
        clock.advance(Duration.ofSeconds(1));

        JsonNode rechained = Wait.until("the copy of AUDIT_RECHAINED from seq " + from, Duration.ofSeconds(30), () ->
                copies(webhook()).stream().filter(c -> "AUDIT_RECHAINED".equals(c.path("action").asString(null))
                        && c.path("changes").path("fromSeq").asLong(-1) == from).findFirst().orElse(null));
        List<JsonNode> sentAfter = new ArrayList<>();
        List<Received> all = webhook().requests();
        for (Received r : all.subList(receivedBefore, all.size())) {
            if (r.path().equals(COPY_PATH)) {
                sentAfter.add(r.json());
            }
        }
        JsonNode renameNow = byId(entriesOf(admin, person.accountId())).get(rename);
        List<JsonNode> renameCopies = sentAfter.stream().filter(c -> c.path("id").asLong(-1) == rename).toList();
        assertThat(renameCopies).as("copies of the rename sent after anonymizing").isNotEmpty();
        JsonNode renameCopy = renameCopies.getLast();
        assertThat(chainJson(renameCopy)).as("the rename's copy").isEqualTo(chainJson(renameNow))
                .doesNotContain(renamed).doesNotContain(person.email());
        assertThat(text(renameCopy, "hash")).as("hash of the rename's copy").isEqualTo(text(renameNow, "hash"));
        assertThat(sentAfter).as("copies sent after anonymizing").extracting(c -> c.path("id").asLong(-1))
                .doesNotContain(invitation);
        assertThat(copies(webhook()).stream().filter(c -> c.path("id").asLong(-1) == invitation).count())
                .as("copies of the invitation entry, sent once before anonymizing").isEqualTo(1);
        assertThat(text(rechained, "hash")).as("hash of the AUDIT_RECHAINED copy")
                .matches(HASH_PATTERN);
    }
}
