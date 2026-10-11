package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.LOG;
import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.json;
import static io.popdesk.audit.AuditSteps.log;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.AuditSteps.time;
import static io.popdesk.support.AuditChainMath.HASH_PATTERN;
import static io.popdesk.support.AuditChainMath.number;
import static io.popdesk.support.AuditChainMath.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reading the audit log as an admin: order, pages, and the filters (kind, who did it, what it was
 * done to, period). Each test acts as an admin of its own and narrows the log to that admin or to
 * the companies and accounts it made, because every test of the context writes to the same log.
 */
class AuditLogViewTest extends BareAppTest {

    private static final String COMPANY_CREATED = "COMPANY_CREATED";

    private Session newAdmin() {
        return fixtures.admin(fixtures.signInAdmin());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static List<String> codes(List<JsonNode> entries) {
        return entries.stream().map(e -> e.path("target").path("code").asString(null)).toList();
    }

    private static List<String> actions(List<JsonNode> entries) {
        return entries.stream().map(e -> text(e, "action")).toList();
    }

    private static List<JsonNode> items(ApiResponse page) {
        List<JsonNode> out = new ArrayList<>();
        page.json().path("items").forEach(out::add);
        return out;
    }

    private static List<String> fieldErrors(ApiResponse response) {
        List<String> out = new ArrayList<>();
        response.json().path("fields").forEach(f -> out.add(f.path("field").asString(null) + ":"
                + f.path("code").asString(null)));
        return out;
    }

    /** Checks every field of the entry of a company this admin made at {@code at}. */
    private static void assertCreatedEntry(JsonNode entry, Session admin, Company company, Instant at) {
        assertThat(entry.propertyNames()).as("fields of %s", entry).containsExactlyInAnyOrder("id", "seq",
                "action", "actor", "target", "changes", "occurredAt", "hash", "prevHash");
        assertThat(number(entry, "id")).as("id").isPositive();
        assertThat(number(entry, "seq")).as("seq").isPositive();
        assertThat(text(entry, "action")).isEqualTo(COMPANY_CREATED);
        assertThat(canonical(entry.path("actor"))).as("who did it").isEqualTo(canonical(json(Map.of(
                "accountId", admin.accountId(), "name", admin.name(), "email", admin.email()))));
        assertThat(canonical(entry.path("target"))).as("what it was done to").isEqualTo(canonical(json(Map.of(
                "type", "COMPANY", "code", company.code()))));
        assertThat(canonical(entry.path("changes"))).as("values of a new company, as stored")
                .isEqualTo(canonical(json(Map.of("code", company.code(), "name", company.name(), "legalName",
                        company.legalName(), "status", "ACTIVE"))));
        assertThat(time(entry)).as("occurredAt").isEqualTo(at);
        assertThat(text(entry, "hash")).as("hash").matches(HASH_PATTERN);
        assertThat(text(entry, "prevHash")).as("prevHash").matches(HASH_PATTERN)
                .isNotEqualTo(text(entry, "hash"));
    }

    @Test
    @DisplayName("S1B-AC-01 the log lists entries newest first, entries of the same time by seq highest first, in "
            + "pages; an entry has its kind, who did it, the target, the changed values, the time, and the hashes")
    void newestFirstInPages() {
        Session admin = newAdmin();
        Instant firstAt = now();
        Company first = fixtures.createCompany(admin);
        clock.advance(Duration.ofSeconds(1));
        Instant laterAt = now();
        Company second = fixtures.createCompany(admin);
        Company third = fixtures.createCompany(admin);
        Object[] mine = {"actorAccountId", admin.accountId(), "action", COMPANY_CREATED};

        ApiResponse pageOne = log(admin, mine).query("size", 2).expect(200);
        ApiResponse pageTwo = log(admin, mine).query("size", 2).query("page", 2).expect(200);

        assertThat(pageOne.jsonLong("page")).as("page").isEqualTo(1);
        assertThat(pageOne.jsonLong("size")).as("size").isEqualTo(2);
        assertThat(pageOne.jsonLong("totalElements")).as("totalElements").isEqualTo(3);
        assertThat(pageOne.jsonLong("totalPages")).as("totalPages").isEqualTo(2);
        assertThat(pageTwo.jsonLong("page")).as("page of the second page").isEqualTo(2);
        assertThat(codes(items(pageOne))).as("first page, newest first").containsExactly(third.code(),
                second.code());
        assertThat(codes(items(pageTwo))).as("second page").containsExactly(first.code());
        assertCreatedEntry(items(pageOne).get(0), admin, third, laterAt);
        assertCreatedEntry(items(pageOne).get(1), admin, second, laterAt);
        assertCreatedEntry(items(pageTwo).get(0), admin, first, firstAt);
        assertThat(List.of(number(items(pageOne).get(0), "seq"), number(items(pageOne).get(1), "seq"),
                number(items(pageTwo).get(0), "seq"))).as("seq of the three, newest first")
                .isSortedAccordingTo((a, b) -> Long.compare(b, a)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("S1B-AC-03 a kind filter gives only entries of that kind, and several kinds together give the "
            + "entries of any of them")
    void filtersByKinds() {
        Session admin = newAdmin();
        Company company = fixtures.createCompany(admin);
        admin.client().patch("/api/console/companies/" + company.code())
                .json(Map.of("name", Unique.text("Renamed company"))).expect(200);
        admin.client().put("/api/console/companies/" + company.code() + "/status")
                .json(Map.of("status", "SUSPENDED")).expect(200);

        List<JsonNode> all = entries(admin, "targetCode", company.code());
        List<JsonNode> one = entries(admin, "targetCode", company.code(), "action", "COMPANY_UPDATED");
        List<JsonNode> two = entries(admin, "targetCode", company.code(), "action", COMPANY_CREATED, "action",
                "COMPANY_STATUS_CHANGED");

        assertThat(actions(all)).as("without a kind filter").containsExactly("COMPANY_STATUS_CHANGED",
                "COMPANY_UPDATED", COMPANY_CREATED);
        assertThat(actions(one)).as("one kind").containsExactly("COMPANY_UPDATED");
        assertThat(actions(two)).as("two kinds").containsExactly("COMPANY_STATUS_CHANGED", COMPANY_CREATED);
    }

    @Test
    @DisplayName("S1B-AC-03 a kind the log does not know is 400 INVALID_INPUT with UNKNOWN_VALUE on action")
    void unknownKindIsRejected() {
        Session admin = newAdmin();
        fixtures.createCompany(admin);

        ApiResponse response = log(admin, "actorAccountId", admin.accountId(), "action", COMPANY_CREATED, "action",
                "COMPANY_PAINTED").send();

        assertThat(response.status()).as(response.request()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldErrors(response)).as("fields").containsExactly("action:UNKNOWN_VALUE");
    }

    @Test
    @DisplayName("S1B-AC-04 an actor filter gives only what that account did")
    void filtersByActor() {
        Session admin = newAdmin();
        Session other = newAdmin();
        Company mine = fixtures.createCompany(admin);
        Company theirs = fixtures.createCompany(other);

        List<JsonNode> found = entries(admin, "actorAccountId", admin.accountId());

        assertThat(found).as("entries of account %d", admin.accountId()).isNotEmpty()
                .allSatisfy(e -> assertThat(number(e.path("actor"), "accountId")).as("actor of %s", e)
                        .isEqualTo(admin.accountId()));
        assertThat(codes(found)).as("companies in the entries").contains(mine.code()).doesNotContain(theirs.code());
    }

    @Test
    @DisplayName("S1B-AC-04 actor 0 gives what popdesk itself did, the first admin's creation among it, and "
            + "nothing a person did")
    void actorZeroIsTheSystem() {
        Session admin = newAdmin();
        fixtures.createCompany(admin);

        List<JsonNode> found = entries(admin, "actorAccountId", 0);

        assertThat(actions(found)).as("kinds done by popdesk itself").contains("FIRST_ADMIN_CREATED");
        assertThat(found).allSatisfy(e -> assertThat(canonical(e.path("actor"))).as("actor of %s", e)
                .isEqualTo("{\"accountId\":0,\"email\":null,\"name\":null}"));
    }

    @Test
    @DisplayName("S1B-AC-05 a target account filter gives the account's entries and its membership entries; with "
            + "target type ACCOUNT the membership entries are left out")
    void filtersByTargetAccount() {
        Session admin = newAdmin();
        Company joined = fixtures.createCompany(admin);
        Company added = fixtures.createCompany(admin);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, joined.code());
        Invitation someoneElse = fixtures.invite(admin, Role.CUSTOMER, joined.code());
        admin.client().put("/api/console/companies/" + added.code() + "/customers/" + customer.accountId())
                .expect(200);

        List<JsonNode> ofAccount = entries(admin, "targetAccountId", customer.accountId());
        List<JsonNode> accountOnly = entries(admin, "targetAccountId", customer.accountId(), "targetType",
                "ACCOUNT");
        JsonNode membership = onlyEntry(admin, "targetAccountId", customer.accountId(), "targetType", "COMPANY",
                "targetCode", added.code());

        assertThat(ofAccount).as("entries that name account %d", customer.accountId())
                .allSatisfy(e -> assertThat(number(e.path("target"), "accountId")).as("target of %s", e)
                        .isEqualTo(customer.accountId()));
        assertThat(actions(ofAccount)).as("without a type").contains("INVITATION_CREATED", "CUSTOMER_ADDED");
        assertThat(accountOnly).as("with type ACCOUNT").isNotEmpty()
                .allSatisfy(e -> assertThat(canonical(e.path("target"))).as("target of %s", e)
                        .isEqualTo(canonical(json(Map.of("type", "ACCOUNT", "accountId", customer.accountId())))));
        assertThat(actions(accountOnly)).contains("INVITATION_CREATED").doesNotContain("CUSTOMER_ADDED");
        assertThat(text(membership, "action")).isEqualTo("CUSTOMER_ADDED");
        assertThat(ofAccount.stream().map(e -> e.path("target").path("accountId").asLong(-1)).toList())
                .as("no entry of another account").doesNotContain(someoneElse.accountId());
    }

    @Test
    @DisplayName("S1B-AC-05 a target code filter gives only the entries of that company")
    void filtersByTargetCode() {
        Session admin = newAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, other.code());
        admin.client().put("/api/console/companies/" + company.code() + "/customers/" + customer.accountId())
                .expect(200);

        List<JsonNode> found = entries(admin, "targetCode", company.code());

        assertThat(actions(found)).as("entries of company %s, newest first", company.code())
                .containsExactly("CUSTOMER_ADDED", COMPANY_CREATED);
        assertThat(codes(found)).containsOnly(company.code());
        assertThat(entries(admin, "targetCode", company.code(), "targetType", "ACCOUNT"))
                .as("the company's entries with type ACCOUNT").isEmpty();
    }

    @Test
    @DisplayName("S1B-AC-06 a period gives the entries from the start time on and before the end time: the entry "
            + "at the start is in, the entry at the end is out")
    void periodHasItsStartAndNotItsEnd() {
        Session admin = newAdmin();
        Company before = fixtures.createCompany(admin);
        clock.advance(Duration.ofSeconds(1));
        Company atStart = fixtures.createCompany(admin);
        clock.advance(Duration.ofSeconds(1));
        Company atEnd = fixtures.createCompany(admin);
        String from = text(onlyEntry(admin, "targetCode", atStart.code()), "occurredAt");
        String to = text(onlyEntry(admin, "targetCode", atEnd.code()), "occurredAt");
        Object[] mine = {"actorAccountId", admin.accountId(), "action", COMPANY_CREATED};

        List<JsonNode> between = entries(admin, mine[0], mine[1], mine[2], mine[3], "from", from, "to", to);
        List<JsonNode> fromOnly = entries(admin, mine[0], mine[1], mine[2], mine[3], "from", from);
        List<JsonNode> toOnly = entries(admin, mine[0], mine[1], mine[2], mine[3], "to", to);

        assertThat(codes(between)).as("from %s to %s", from, to).containsExactly(atStart.code());
        assertThat(codes(fromOnly)).as("from %s", from).containsExactly(atEnd.code(), atStart.code());
        assertThat(codes(toOnly)).as("to %s", to).containsExactly(atStart.code(), before.code());
    }

    @Test
    @DisplayName("S1B-AC-06 a start after the end is 400 INVALID_INPUT with FROM_AFTER_TO on from; a start equal "
            + "to the end is an empty result, not an error")
    void periodTurnedAroundIsRejected() {
        Session admin = newAdmin();
        Company earlier = fixtures.createCompany(admin);
        clock.advance(Duration.ofSeconds(1));
        Company later = fixtures.createCompany(admin);
        String earlierAt = text(onlyEntry(admin, "targetCode", earlier.code()), "occurredAt");
        String laterAt = text(onlyEntry(admin, "targetCode", later.code()), "occurredAt");

        ApiResponse turnedAround = log(admin, "actorAccountId", admin.accountId(), "from", laterAt, "to", earlierAt)
                .send();
        ApiResponse empty = log(admin, "actorAccountId", admin.accountId(), "from", laterAt, "to", laterAt).send();

        assertThat(turnedAround.status()).as(turnedAround.request()).isEqualTo(400);
        assertThat(turnedAround.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldErrors(turnedAround)).as("fields").containsExactly("from:FROM_AFTER_TO");
        assertThat(empty.status()).as(empty.request()).isEqualTo(200);
        assertThat(empty.json().path("items")).as("entries of an empty period").isEmpty();
        assertThat(empty.jsonLong("totalElements")).isZero();
    }

    @Test
    @DisplayName("S1B-AC-08 PUT, PATCH, and DELETE on the audit log and on an entry are 405 or 404, and the entry "
            + "stays as it was")
    void noRequestChangesOrDeletesAnEntry() {
        Session admin = newAdmin();
        Company company = fixtures.createCompany(admin);
        JsonNode entry = onlyEntry(admin, "targetCode", company.code());
        List<String> answers = new ArrayList<>();

        for (String path : List.of(LOG, LOG + "/" + number(entry, "id"), LOG + "/export", LOG + "/verify")) {
            for (String method : List.of("PUT", "PATCH", "DELETE")) {
                ApiResponse response = admin.client().request(method, path)
                        .json(Map.of("action", "COMPANY_UPDATED", "changes", Map.of())).send();
                if (response.status() != 405 && response.status() != 404) {
                    answers.add(method + " " + path + " -> " + response.status());
                }
            }
        }

        assertThat(answers).as("requests that were not answered 405 or 404").isEmpty();
        assertThat(onlyEntry(admin, "targetCode", company.code())).as("the entry afterwards").isEqualTo(entry);
    }
}
