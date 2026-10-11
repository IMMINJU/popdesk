package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertFieldError;
import static io.popdesk.project.PeopleSteps.accounts;
import static io.popdesk.project.PeopleSteps.ids;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The {@code status} filter and the {@code q} search of the account list. The {@code role} filter is
 * from the first slice ({@link AccountListRoleTest}). The context is shared, so every check counts
 * only accounts this test made, or holds for every account in the answer.
 */
class AccountListFilterTest extends BareAppTest {

    private static final String ACCOUNTS = "/api/console/accounts";

    /** A word no other account has, in letters only so that it holds neither {@code %} nor {@code _}. */
    private static String word() {
        StringBuilder out = new StringBuilder("zq");
        for (char digit : Long.toString(Unique.number()).toCharArray()) {
            out.append((char) ('a' + (digit - '0')));
        }
        return out.append("kv").toString();
    }

    private static boolean holds(JsonNode account, String text) {
        String needle = text.toLowerCase(Locale.ROOT);
        return account.path("name").asString("").toLowerCase(Locale.ROOT).contains(needle)
                || account.path("email").asString("").toLowerCase(Locale.ROOT).contains(needle);
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"INVITED", "ACTIVE", "SUSPENDED"})
    @DisplayName("S1A-AC-29 the status filter gives only accounts of that status, and the test's own account of it")
    void statusFilter(String status) {
        Session admin = fixtures.signInAdmin();
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        Session active = fixtures.agent(admin);
        Session suspended = fixtures.agent(admin);
        PeopleSteps.suspend(admin, suspended.accountId());
        long expected = switch (status) {
            case "INVITED" -> invited.accountId();
            case "ACTIVE" -> active.accountId();
            default -> suspended.accountId();
        };

        List<JsonNode> listed = accounts(admin, "status", status);

        assertThat(listed).as("statuses in the list of %s accounts", status)
                .extracting(n -> n.path("status").asString(null)).containsOnly(status);
        assertThat(ids(listed)).as("of this test's three accounts, those with status=%s", status)
                .filteredOn(List.of(invited.accountId(), active.accountId(), suspended.accountId())::contains)
                .containsExactly(expected);
    }

    @Test
    @DisplayName("S1A-AC-29 q finds accounts by a part of the name or of the email, ignoring case, and nothing else")
    void searchByNameOrEmail() {
        Session admin = fixtures.signInAdmin();
        String inName = word();
        String inEmail = word();
        Invitation byName = fixtures.invite(admin, Role.AGENT, Unique.email("agent"), "Kim " + inName + " Lee",
                List.of());
        Invitation byEmail = fixtures.invite(admin, Role.AGENT, "a." + inEmail + ".b-" + Unique.number()
                + "@example.test", Unique.text("agent"), List.of());

        List<JsonNode> foundByName = accounts(admin, "q", inName.toUpperCase(Locale.ROOT));
        List<JsonNode> foundByEmail = accounts(admin, "q", inEmail.substring(1));

        assertThat(ids(foundByName)).as("accounts found by a part of the name").containsExactly(byName.accountId());
        assertThat(ids(foundByEmail)).as("accounts found by a part of the email")
                .containsExactly(byEmail.accountId());
        assertThat(accounts(admin, "q", word())).as("accounts found by a word no account has").isEmpty();
    }

    @Test
    @DisplayName("S1A-AC-29 q, role, and status narrow the list together, and the result is split into pages (COM-4)")
    void filtersCombineAndPage() {
        Session admin = fixtures.signInAdmin();
        String shared = word();
        Invitation invitedAgent = invite(admin, Role.AGENT, shared);
        Invitation secondInvitedAgent = invite(admin, Role.AGENT, shared);
        Invitation invitedAdmin = invite(admin, Role.ADMIN, shared);
        Session activeAgent = fixtures.accept(invite(admin, Role.AGENT, shared));

        List<JsonNode> all = accounts(admin, "q", shared);
        List<JsonNode> narrowed = accounts(admin, "q", shared, "role", "AGENT", "status", "INVITED");
        ApiResponse firstPage = admin.client().get(ACCOUNTS).query("q", shared).query("page", 1).query("size", 3)
                .expect(200);
        ApiResponse secondPage = admin.client().get(ACCOUNTS).query("q", shared).query("page", 2).query("size", 3)
                .expect(200);

        assertThat(ids(all)).as("accounts found by the shared word").containsExactlyInAnyOrder(
                invitedAgent.accountId(), secondInvitedAgent.accountId(), invitedAdmin.accountId(),
                activeAgent.accountId());
        assertThat(ids(narrowed)).as("invited agents found by the shared word")
                .containsExactlyInAnyOrder(invitedAgent.accountId(), secondInvitedAgent.accountId());
        assertThat(firstPage.jsonLong("totalElements")).isEqualTo(4);
        assertThat(firstPage.jsonLong("totalPages")).isEqualTo(2);
        assertThat(firstPage.jsonLong("page")).isEqualTo(1);
        assertThat(firstPage.jsonLong("size")).isEqualTo(3);
        assertThat(firstPage.json().path("items").size()).isEqualTo(3);
        assertThat(secondPage.json().path("items").size()).isEqualTo(1);
    }

    private Invitation invite(Session admin, Role role, String wordInName) {
        return fixtures.invite(admin, role, Unique.email("listed"), wordInName + " " + Unique.number(), List.of());
    }

    @Test
    @DisplayName("S1A-AC-29 q is 1 to 100 characters: empty is 400 TOO_SHORT, 101 is 400 TOO_LONG, 100 lists")
    void searchTextLimits() {
        Session admin = fixtures.signInAdmin();

        assertFieldError(admin.client().get(ACCOUNTS).query("q", "").send(), 400, "q", "TOO_SHORT");
        assertFieldError(admin.client().get(ACCOUNTS).query("q", "a".repeat(101)).send(), 400, "q", "TOO_LONG");
        ApiResponse longest = admin.client().get(ACCOUNTS).query("q", "a".repeat(100)).expect(200);
        assertThat(longest.jsonLong("totalElements")).as("accounts whose name or email holds 100 a's").isZero();
    }

    @Test
    @DisplayName("S1A-AC-29 a status that is none of the three is 400 with field status UNKNOWN_VALUE")
    void unknownStatus() {
        Session admin = fixtures.signInAdmin();
        fixtures.invite(admin, Role.AGENT);

        assertFieldError(admin.client().get(ACCOUNTS).query("status", "NOSUCHSTATUS").send(), 400, "status",
                "UNKNOWN_VALUE");
        assertThat(accounts(admin, "status", "ACTIVE")).as("statuses in the list of ACTIVE accounts")
                .extracting(n -> n.path("status").asString(null)).isNotEmpty().containsOnly("ACTIVE");
    }

    @ParameterizedTest(name = "{0} is found by {2} and not by a wildcard")
    @CsvSource({
            "pct%sign, pct-sign, t%s, %",
            "under_score, under-score, r_s, _",
    })
    @DisplayName("S1A-AC-89 % and _ in q are ordinary characters: they find only accounts that hold them")
    void wildcardsAreOrdinaryCharacters(String name, String decoyName, String part, String wildcard) {
        Session admin = fixtures.signInAdmin();
        String tail = " " + word();
        Invitation target = fixtures.invite(admin, Role.AGENT, Unique.email("agent"), name + tail, List.of());
        Invitation decoy = fixtures.invite(admin, Role.AGENT, Unique.email("agent"), decoyName + tail, List.of());

        List<JsonNode> byWildcard = accounts(admin, "q", wildcard);
        List<JsonNode> byPart = accounts(admin, "q", part);
        List<JsonNode> byPartAndTail = accounts(admin, "q", name.substring(name.indexOf(wildcard)) + tail);

        assertThat(ids(byWildcard)).as("accounts found by %s", wildcard).contains(target.accountId())
                .doesNotContain(decoy.accountId());
        assertThat(byWildcard).as("accounts found by %s all hold it", wildcard).allMatch(n -> holds(n, wildcard));
        assertThat(ids(byPart)).as("accounts found by %s", part).contains(target.accountId())
                .doesNotContain(decoy.accountId());
        assertThat(byPart).as("accounts found by %s all hold it", part).allMatch(n -> holds(n, part));
        assertThat(ids(byPartAndTail)).as("accounts found by the end of the name").containsExactly(target.accountId());
    }
}
