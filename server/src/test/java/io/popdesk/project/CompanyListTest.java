package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.allValues;
import static io.popdesk.project.ProjectChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who sees which companies in the company list. */
class CompanyListTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-32 an admin's company list has every company")
    void adminSeesAll() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session otherAdmin = fixtures.admin(admin);

        assertThat(allValues(admin.client(), "/api/console/companies", "code")).contains(a.code(), b.code());
        assertThat(allValues(otherAdmin.client(), "/api/console/companies", "code")).contains(a.code(), b.code());
    }

    @Test
    @DisplayName("S0-AC-32 an agent's company list has only the companies they are assigned to")
    void agentSeesAssigned() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Company c = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, a.code(), b.code());
        // c is assigned to someone else, so a list of every assigned company would hold it.
        Session agentOfC = fixtures.agent(admin, c.code());
        assertThat(allValues(agentOfC.client(), "/api/console/companies", "code")).contains(c.code());

        assertThat(allValues(agent.client(), "/api/console/companies", "code"))
                .contains(a.code(), b.code())
                .doesNotContain(c.code());
        assertError(agent.client().get("/api/console/companies/" + c.code()).send(), 404, "NOT_FOUND");
        assertThat(agent.client().get("/api/console/companies/" + a.code()).expect(200).jsonText("code"))
                .isEqualTo(a.code());
    }

    @Test
    @DisplayName("S0-AC-32 an agent with no assignment gets an empty company list")
    void unassignedAgentSeesNone() {
        Session admin = fixtures.signInAdmin();
        fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin);
        // Another agent has an assignment, whatever ran before in this context.
        fixtures.agent(admin, fixtures.createCompany(admin).code());

        ApiResponse list = agent.client().get("/api/console/companies").expect(200);

        assertThat(list.json().path("items").isArray()).isTrue();
        assertThat(list.json().path("items").size()).isZero();
        assertThat(list.jsonLong("totalElements")).isZero();
    }

    @Test
    @DisplayName("S0-AC-32 a customer's company list has only the companies they belong to")
    void customerSeesOwn() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Company c = fixtures.createCompany(admin);
        Session inA = fixtures.customer(admin, a.code());
        Session inAandB = fixtures.customer(admin, a.code(), b.code());

        assertThat(allValues(inA.client(), "/api/portal/companies", "code"))
                .contains(a.code())
                .doesNotContain(b.code(), c.code());
        assertThat(allValues(inAandB.client(), "/api/portal/companies", "code"))
                .contains(a.code(), b.code())
                .doesNotContain(c.code());
        assertThat(inA.client().get("/api/portal/companies").expect(200).jsonLong("totalElements")).isEqualTo(1);
    }
}
