package io.popdesk.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Who sees which companies in the statistics (STATS-2, TM-62): admins every company, an agent the companies they are
 * assigned to and nothing of the others, customers what any console path gives them.
 */
class StatsScopeTest extends BareAppTest {

    @Test
    @DisplayName("S7B-AC-03 an admin sees every company, an agent of A sees A alone with the admin's counts, an agent "
            + "assigned nowhere sees no item at all")
    void agentSeesAssignedCompaniesOnly() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        fixtures.fileIssue(fixtures.customer(admin, a.code()), a.code());
        fixtures.fileIssue(fixtures.customer(admin, b.code()), b.code());
        fixtures.fileIssue(fixtures.customer(admin, b.code()), b.code());
        Session agentOfA = fixtures.agent(admin, a.code());
        Session agentOfB = fixtures.agent(admin, b.code());
        Session nowhere = fixtures.agent(admin);

        List<String> ofAdmin = StatsSteps.codes(admin);
        List<JsonNode> ofAgent = StatsSteps.items(agentOfA);

        assertThat(ofAdmin).as("companies of the admin").contains(a.code(), b.code());
        assertThat(StatsSteps.codes(ofAgent)).as("companies of the agent of A").containsExactly(a.code());
        assertThat(StatsSteps.counts(ofAgent.getFirst())).as("counts of A for its agent")
                .isEqualTo(StatsSteps.counts(StatsSteps.itemOf(admin, a.code())));
        assertThat(StatsSteps.codes(agentOfB)).as("companies of the agent of B").containsExactly(b.code());
        assertThat(StatsSteps.items(nowhere)).as("items of an agent assigned nowhere").isEmpty();
    }

    @Test
    @DisplayName("S7B-AC-03 a customer reading the statistics gets what a console path already gives a customer: "
            + "404 on the portal host, 401 with the session carried to the console host")
    void customerGetsTheConsoleAnswer() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);

        for (ApiClient client : List.of(customer.client(), carried)) {
            ApiResponse existing = client.get("/api/console/companies").send();
            ApiResponse stats = client.get(StatsSteps.STATS).send();

            assertThat(stats.status()).as(stats.request() + " -> " + stats.snippet()).isEqualTo(existing.status());
            assertThat(stats.errorCode()).as("code of " + stats.snippet()).isEqualTo(existing.errorCode());
        }
        assertThat(customer.client().get(StatsSteps.STATS).send().status()).as("on the portal host").isEqualTo(404);
        assertThat(carried.get(StatsSteps.STATS).send().status()).as("on the console host").isEqualTo(401);
    }
}
