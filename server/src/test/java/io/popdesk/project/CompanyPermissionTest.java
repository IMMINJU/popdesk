package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.allValues;
import static io.popdesk.project.ProjectChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Agents may not create companies or change assignments. */
class CompanyPermissionTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-30 an agent creating a company gets 403 FORBIDDEN and nothing is created")
    void agentCannotCreateCompany() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String code = Unique.companyCode();

        assertError(agent.client().post("/api/console/companies")
                .json(Map.of("code", code, "name", "By agent", "legalName", "By agent Ltd."))
                .send(), 403, "FORBIDDEN");

        assertError(admin.client().get("/api/console/companies/" + code).send(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-30 an agent assigning an agent gets 403 FORBIDDEN, even on a company they are assigned to")
    void agentCannotAssign() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Invitation other = fixtures.invite(admin, Role.AGENT);

        assertError(agent.client().put("/api/console/companies/" + company.code() + "/agents/" + other.accountId())
                .send(), 403, "FORBIDDEN");

        assertThat(allValues(admin.client(), "/api/console/companies/" + company.code() + "/agents", "email"))
                .contains(agent.email())
                .doesNotContain(other.email());
    }

    @Test
    @DisplayName("S0-AC-30 an agent assigning on a company they cannot see still gets 403, checked before the target")
    void agentGets403BeforeTargetCheck() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin);

        assertError(agent.client().put("/api/console/companies/" + company.code() + "/agents/" + agent.accountId())
                .send(), 403, "FORBIDDEN");
        assertError(agent.client().put("/api/console/companies/NOSUCH9/agents/" + agent.accountId()).send(), 403,
                "FORBIDDEN");

        assertThat(allValues(admin.client(), "/api/console/companies/" + company.code() + "/agents", "email"))
                .doesNotContain(agent.email());
    }
}
