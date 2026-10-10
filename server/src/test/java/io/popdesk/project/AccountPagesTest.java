package io.popdesk.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pages of the account list (conventions.md "Pagination", COM-4): the totals count every account
 * the list would give. The other tests of this list read every page and look at the items only.
 *
 * <p>Other tests of this context make accounts too, so the totals are at least what this test made.
 */
class AccountPagesTest extends BareAppTest {

    @Test
    @DisplayName("MUT AccountRepository the account list gives the number of accounts as totalElements and the pages they fill as totalPages")
    void accountListGivesTotals() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        for (int i = 0; i < 3; i++) {
            fixtures.customer(admin, company.code());
        }

        ApiResponse customers = admin.client().get("/api/console/accounts").query("role", "CUSTOMER")
                .query("size", 2).expect(200);
        ApiResponse everyone = admin.client().get("/api/console/accounts").query("size", 2).expect(200);

        long customerTotal = customers.json().path("totalElements").asLong();
        long total = everyone.json().path("totalElements").asLong();
        assertThat(customers.json().path("items").size()).as("customers on the first page").isEqualTo(2);
        assertThat(customerTotal).as("customers").isGreaterThanOrEqualTo(3);
        assertThat(customers.json().path("totalPages").asLong()).as("pages of %s customers, two a page", customerTotal)
                .isEqualTo((customerTotal + 1) / 2);
        assertThat(total).as("accounts of every role: the customers and the admin").isGreaterThan(customerTotal);
        assertThat(everyone.json().path("totalPages").asLong()).as("pages of %s accounts, two a page", total)
                .isEqualTo((total + 1) / 2);
    }
}
