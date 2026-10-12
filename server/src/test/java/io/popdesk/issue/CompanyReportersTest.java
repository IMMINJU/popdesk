package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/console/companies/{code}/reporters}: the customers staff can file an issue for.
 * Only the company's active customers, by name, for an admin and for an agent assigned to the
 * company; an agent who is not assigned gets the 404 of a code that does not exist.
 */
class CompanyReportersTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static ApiRequest reporters(Session staff, String code) {
        return staff.client().get("/api/console/companies/" + code + "/reporters");
    }

    private static List<Long> ids(ApiResponse page) {
        return items(page).stream().map(n -> n.path("accountId").asLong(-1)).toList();
    }

    /** An active customer of {@code code} with this name and a unique email that starts with {@code emailStart}. */
    private Session customer(Session admin, String code, String name, String emailStart) {
        return fixtures.accept(fixtures.invite(admin, Role.CUSTOMER, Unique.email(emailStart), name, List.of(code)));
    }

    @Test
    @DisplayName("S3-AC-40 the reporter list holds only the company's active customers, by name, with accountId, name, email, and photoUpdatedAt, in pages")
    void onlyActiveCustomersOfTheCompany() {
        Session admin = flow.admin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        String word = IssueFlow.word();
        Session cho = customer(admin, company.code(), word + " cc", "cho");
        Session ahn = customer(admin, company.code(), word + " aa", "ahn");
        Session bae = customer(admin, company.code(), word + " bb", "bae");
        Invitation invited = fixtures.invite(admin, Role.CUSTOMER, company.code());
        Session suspended = fixtures.customer(admin, company.code());
        flow.suspend(admin, suspended.accountId());
        Session removed = fixtures.customer(admin, company.code(), other.code());
        flow.removeCustomer(admin, company.code(), removed.accountId());
        Session ofOther = fixtures.customer(admin, other.code());
        Session agent = fixtures.agent(admin, company.code());

        for (Session staff : List.of(admin, agent)) {
            ApiResponse page = reporters(staff, company.code()).query("size", 100).expect(200);
            assertThat(ids(page)).as("reporters of %s for %s, by name", company.code(), staff.role())
                    .containsExactly(ahn.accountId(), bae.accountId(), cho.accountId())
                    .doesNotContain(invited.accountId(), suspended.accountId(), removed.accountId(),
                            ofOther.accountId(), agent.accountId());
            assertThat(page.jsonLong("totalElements")).as("totalElements for %s", staff.role()).isEqualTo(3);
            JsonNode first = items(page).getFirst();
            assertThat(first.propertyNames()).as("field names of a reporter")
                    .containsExactlyInAnyOrder("accountId", "name", "email", "photoUpdatedAt");
            assertThat(text(first, "name")).isEqualTo(ahn.name());
            assertThat(text(first, "email")).isEqualTo(ahn.email());
        }
        ApiResponse secondPage = reporters(agent, company.code()).query("page", 2).query("size", 2).expect(200);
        assertThat(ids(secondPage)).as("page 2 of size 2").containsExactly(cho.accountId());
        assertThat(secondPage.jsonLong("totalElements")).isEqualTo(3);
        assertThat(secondPage.jsonLong("totalPages")).isEqualTo(2);
        assertThat(secondPage.jsonLong("page")).isEqualTo(2);
        assertThat(secondPage.jsonLong("size")).isEqualTo(2);
    }

    @Test
    @DisplayName("S3-AC-40 q narrows the reporter list by a part of the name or of the email, ignoring case, and finds no customer of another company, no suspended one, and no invited one")
    void searchByNameOrEmail() {
        World w = flow.world();
        String inName = IssueFlow.word();
        String inEmail = IssueFlow.word();
        Session byName = customer(w.admin(), w.code(), "Kim " + inName + " Lee", "customer");
        Session byEmail = customer(w.admin(), w.code(), Unique.text("customer"), "a." + inEmail + ".b");
        Session inOtherCompany = customer(w.admin(), w.elsewhere().code(), "Park " + inName, "c." + inEmail);
        Session suspended = customer(w.admin(), w.code(), "Choi " + inName, "d." + inEmail);
        flow.suspend(w.admin(), suspended.accountId());
        Invitation invited = fixtures.invite(w.admin(), Role.CUSTOMER, Unique.email("e." + inEmail), "Jung " + inName,
                List.of(w.code()));

        ApiResponse foundByName = reporters(w.agent(), w.code()).query("q", inName.toUpperCase(Locale.ROOT))
                .expect(200);
        ApiResponse foundByEmail = reporters(w.agent(), w.code()).query("q", inEmail.substring(1)).expect(200);
        ApiResponse foundByNothing = reporters(w.agent(), w.code()).query("q", IssueFlow.word()).expect(200);

        assertThat(ids(foundByName)).as("reporters found by a part of the name").containsExactly(byName.accountId())
                .doesNotContain(inOtherCompany.accountId(), suspended.accountId(), invited.accountId());
        assertThat(foundByName.jsonLong("totalElements")).as("totalElements by name").isEqualTo(1);
        assertThat(ids(foundByEmail)).as("reporters found by a part of the email").containsExactly(byEmail.accountId())
                .doesNotContain(inOtherCompany.accountId(), suspended.accountId(), invited.accountId());
        assertThat(foundByEmail.jsonLong("totalElements")).as("totalElements by email").isEqualTo(1);
        assertThat(foundByEmail.text()).as("the answer to the email search holds nobody of another company")
                .doesNotContain(inOtherCompany.email()).doesNotContain(inOtherCompany.name());
        assertThat(ids(foundByNothing)).as("reporters found by a word nobody has").isEmpty();
    }

    @Test
    @DisplayName("S3-AC-40 an agent who is not assigned to the company gets 404, the same body as for a code that does not exist, with or without q")
    void unassignedAgentGetsNotFound() {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session carol = fixtures.customer(w.admin(), other.code());
        String missingCode = Unique.companyCode();
        reporters(w.admin(), other.code()).expect(200);

        ApiResponse missing = reporters(w.agent(), missingCode).send();
        ApiResponse unassigned = reporters(w.agent(), other.code()).send();
        ApiResponse unassignedSearch = reporters(w.agent(), other.code()).query("q", carol.name()).send();
        ApiResponse ofNothing = reporters(w.unassignedAgent(), w.code()).send();

        assertThat(missing.status()).as(missing.request()).isEqualTo(404);
        assertThat(missing.errorCode()).isEqualTo("NOT_FOUND");
        for (ApiResponse refused : List.of(unassigned, unassignedSearch, ofNothing)) {
            assertThat(refused.status()).as(refused.request()).isEqualTo(404);
            assertThat(refused.body()).as("body of %s, like a missing code", refused.request())
                    .isEqualTo(missing.body());
            assertThat(refused.text()).as("body of %s", refused.request()).doesNotContain(carol.name())
                    .doesNotContain(carol.email());
        }
    }
}
