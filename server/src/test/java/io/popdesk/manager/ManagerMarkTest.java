package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.customer;
import static io.popdesk.manager.ManagerSteps.invite;
import static io.popdesk.manager.ManagerSteps.managerOf;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.members;
import static io.popdesk.manager.ManagerSteps.myCompanies;
import static io.popdesk.manager.ManagerSteps.pages;
import static io.popdesk.manager.ManagerSteps.unmark;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * An admin marks a customer of a company as its manager and takes the mark away (console). What the
 * customer may do is read with the session they already had, in the request right after the change.
 */
class ManagerMarkTest extends BareAppTest {

    private static boolean managerFlag(JsonNode entry) {
        assertThat(entry).as("the entry that carries the manager flag").isNotNull();
        assertThat(entry.path("manager").isBoolean()).as("manager is a boolean in " + entry).isTrue();
        return entry.path("manager").booleanValue();
    }

    // S2-AC-01

    @Test
    @DisplayName("S2-AC-01 marking and unmarking a customer applies from their next request with the session they "
            + "had, and doing either again is 200")
    void markAppliesFromTheNextRequest() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        assertError(customer.client().get(members(company.code())).send(), 403, "FORBIDDEN");

        ApiResponse marked = admin.client().put(managerOf(company.code(), customer.accountId())).expect(200);

        assertThat(marked.jsonLong("accountId")).isEqualTo(customer.accountId());
        assertThat(managerFlag(marked.json())).isTrue();
        customer.client().get(members(company.code())).expect(200);

        ApiResponse again = admin.client().put(managerOf(company.code(), customer.accountId())).expect(200);

        assertThat(managerFlag(again.json())).isTrue();
        customer.client().get(members(company.code())).expect(200);

        ApiResponse unmarked = admin.client().delete(managerOf(company.code(), customer.accountId())).expect(200);

        assertThat(unmarked.jsonLong("accountId")).isEqualTo(customer.accountId());
        assertThat(managerFlag(unmarked.json())).isFalse();
        assertError(customer.client().get(members(company.code())).send(), 403, "FORBIDDEN");

        ApiResponse unmarkedAgain = admin.client().delete(managerOf(company.code(), customer.accountId()))
                .expect(200);

        assertThat(managerFlag(unmarkedAgain.json())).isFalse();
        assertThat(managerFlag(customer(admin, company.code(), customer.accountId()))).isFalse();
    }

    // S2-AC-02

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"customer of another company", "agent of the company", "account that does not exist"})
    @DisplayName("S2-AC-02 marking or unmarking an account that is not a customer of the company is 404 NOT_FOUND "
            + "and marks nobody")
    void onlyCustomersOfTheCompany(String target) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Session member = fixtures.customer(admin, company.code());
        long accountId = switch (target) {
            case "customer of another company" -> fixtures.customer(admin, other.code()).accountId();
            case "agent of the company" -> fixtures.agent(admin, company.code()).accountId();
            default -> 999_999_999L;
        };

        ApiResponse put = admin.client().put(managerOf(company.code(), accountId)).send();
        ApiResponse delete = admin.client().delete(managerOf(company.code(), accountId)).send();

        assertError(put, 404, "NOT_FOUND");
        assertError(delete, 404, "NOT_FOUND");
        assertThat(pages(admin.client(), "/api/console/companies/" + company.code() + "/customers"))
                .as("customers of the company")
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.path("accountId").asLong(-1)).isEqualTo(member.accountId());
                    assertThat(managerFlag(c)).isFalse();
                });
        if (target.equals("customer of another company")) {
            assertThat(managerFlag(customer(admin, other.code(), accountId))).isFalse();
        }
    }

    @Test
    @DisplayName("S2-AC-02 an agent marking or unmarking a manager gets 403 FORBIDDEN and the customer stays as "
            + "they were")
    void agentsCannotMark() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session customer = fixtures.customer(admin, company.code());

        ApiResponse put = agent.client().put(managerOf(company.code(), customer.accountId())).send();
        ApiResponse delete = agent.client().delete(managerOf(company.code(), customer.accountId())).send();

        assertError(put, 403, "FORBIDDEN");
        assertError(delete, 403, "FORBIDDEN");
        assertThat(managerFlag(customer(admin, company.code(), customer.accountId()))).isFalse();
    }

    // S2-AC-03

    @Test
    @DisplayName("S2-AC-03 a manager of company A who also belongs to company B is no manager there: B's member "
            + "list and inviting to B are 403, and their own companies say manager true for A and false for B")
    void markIsPerCompany() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, a.code(), b.code());
        Session ofB = fixtures.customer(admin, b.code());
        String email = Unique.email("to-b");

        mark(admin, a.code(), person.accountId());

        person.client().get(members(a.code())).expect(200);
        assertError(person.client().get(members(b.code())).send(), 403, "FORBIDDEN");
        assertError(invite(person, b.code(), email, "Invited to B"), 403, "FORBIDDEN");
        assertThat(managerFlag(myCompanies(person).get(a.code()))).as("manager of A").isTrue();
        assertThat(managerFlag(myCompanies(person).get(b.code()))).as("manager of B").isFalse();
        assertThat(managerFlag(customer(admin, b.code(), person.accountId()))).isFalse();
        assertThat(pages(admin.client(), "/api/console/companies/" + b.code() + "/customers"))
                .extracting(c -> c.path("accountId").asLong(-1))
                .containsExactlyInAnyOrder(person.accountId(), ofB.accountId());
    }

    @Test
    @DisplayName("S2-AC-03 a company can have several managers")
    void severalManagers() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session first = fixtures.customer(admin, company.code());
        Session second = fixtures.customer(admin, company.code());

        mark(admin, company.code(), first.accountId());
        mark(admin, company.code(), second.accountId());

        assertThat(managerFlag(customer(admin, company.code(), first.accountId()))).isTrue();
        assertThat(managerFlag(customer(admin, company.code(), second.accountId()))).isTrue();
        first.client().get(members(company.code())).expect(200);
        second.client().get(members(company.code())).expect(200);
    }

    // S2-AC-04

    @Test
    @DisplayName("S2-AC-04 a manager taken out of the company and added again is no manager: manager false and the "
            + "member list is 403")
    void leavingDropsTheMark() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session person = ManagerSteps.manager(fixtures, admin, company.code());
        person.client().get(members(company.code())).expect(200);

        ManagerSteps.removeCustomer(admin, company.code(), person.accountId());
        ManagerSteps.addCustomer(admin, company.code(), person.accountId());

        assertThat(managerFlag(customer(admin, company.code(), person.accountId()))).isFalse();
        assertThat(managerFlag(myCompanies(person).get(company.code()))).isFalse();
        assertError(person.client().get(members(company.code())).send(), 403, "FORBIDDEN");
    }

    // S2-AC-05

    @Test
    @DisplayName("S2-AC-05 a customer who has not accepted the invitation yet can be marked, and is a manager "
            + "once they accept")
    void invitedCustomerCanBeMarked() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, company.code());

        ApiResponse marked = admin.client().put(managerOf(company.code(), invitation.accountId())).expect(200);

        assertThat(marked.jsonText("status")).isEqualTo("INVITED");
        assertThat(managerFlag(marked.json())).isTrue();
        Session person = fixtures.accept(invitation);
        assertThat(managerFlag(myCompanies(person).get(company.code()))).isTrue();
        person.client().get(members(company.code())).expect(200);
    }

    // S2-AC-06

    @Test
    @DisplayName("S2-AC-06 in an ARCHIVED company marking is 400 COMPANY_NOT_ACTIVE and marks nobody; unmarking "
            + "still works")
    void archivedCompany() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session marked = ManagerSteps.manager(fixtures, admin, company.code());
        Session plain = fixtures.customer(admin, company.code());
        ManagerSteps.setCompanyStatus(admin, company.code(), "ARCHIVED");

        ApiResponse refused = admin.client().put(managerOf(company.code(), plain.accountId())).send();

        assertError(refused, 400, "COMPANY_NOT_ACTIVE");
        assertThat(managerFlag(customer(admin, company.code(), plain.accountId()))).isFalse();
        assertThat(managerFlag(customer(admin, company.code(), marked.accountId()))).isTrue();

        ApiResponse unmarked = unmark(admin, company.code(), marked.accountId());

        assertThat(managerFlag(unmarked.json())).isFalse();
        assertThat(managerFlag(customer(admin, company.code(), marked.accountId()))).isFalse();
    }

    // S2-AC-07

    @Test
    @DisplayName("S2-AC-07 the console's customer list marks each customer, and the portal's own profile and "
            + "company list say for each company whether the caller is its manager")
    void markIsShown() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        Session plain = fixtures.customer(admin, company.code());

        assertThat(managerFlag(customer(admin, company.code(), manager.accountId()))).isTrue();
        assertThat(managerFlag(customer(admin, company.code(), plain.accountId()))).isFalse();
        assertThat(managerFlag(myCompanies(manager).get(company.code()))).isTrue();
        assertThat(managerFlag(myCompanies(plain).get(company.code()))).isFalse();
        assertThat(pages(manager.client(), "/api/portal/companies")).singleElement().satisfies(c -> {
            assertThat(c.path("code").asString(null)).isEqualTo(company.code());
            assertThat(managerFlag(c)).isTrue();
        });
        assertThat(pages(plain.client(), "/api/portal/companies")).singleElement().satisfies(c -> {
            assertThat(c.path("code").asString(null)).isEqualTo(company.code());
            assertThat(managerFlag(c)).isFalse();
        });
    }
}
