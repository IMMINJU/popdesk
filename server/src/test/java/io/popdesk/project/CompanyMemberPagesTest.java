package io.popdesk.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pages of a company's customers and agents (conventions.md "Pagination", COM-4): the totals count
 * the members of that company. The other tests of these lists read every page and look at the items
 * only.
 */
class CompanyMemberPagesTest extends BareAppTest {

    @Test
    @DisplayName("MUT CompanyRepository the customers and agents lists of a company give its member counts as totalElements and totalPages")
    void memberListsGiveTotals() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        for (int i = 0; i < 3; i++) {
            fixtures.customer(admin, company.code());
        }
        fixtures.customer(admin, other.code());
        fixtures.agent(admin, company.code());
        fixtures.agent(admin, company.code(), other.code());
        fixtures.agent(admin, other.code());

        ApiResponse customers = admin.client().get("/api/console/companies/" + company.code() + "/customers")
                .query("size", 2).expect(200);
        ApiResponse agents = admin.client().get("/api/console/companies/" + company.code() + "/agents")
                .query("size", 2).expect(200);

        assertThat(customers.json().path("items").size()).as("customers on the first page").isEqualTo(2);
        assertThat(customers.json().path("totalElements").asLong()).as("customers of the company").isEqualTo(3);
        assertThat(customers.json().path("totalPages").asInt()).isEqualTo(2);
        assertThat(agents.json().path("items").size()).as("agents on the first page").isEqualTo(2);
        assertThat(agents.json().path("totalElements").asLong()).as("agents of the company").isEqualTo(2);
        assertThat(agents.json().path("totalPages").asInt()).isEqualTo(1);
    }
}
