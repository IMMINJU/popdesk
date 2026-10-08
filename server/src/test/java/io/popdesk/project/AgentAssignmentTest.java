package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.allItems;
import static io.popdesk.project.ProjectChecks.allValues;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Assigning agents to companies, before and after they accept. */
class AgentAssignmentTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-31 a signed-in agent sees the company's issues from the next request after being assigned")
    void assignedAgentSeesIssuesFromNextRequest() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Issue issue = fixtures.fileIssue(fixtures.customer(admin, company.code()), company.code());
        Session agent = fixtures.agent(admin);
        assertError(agent.client().get("/api/console/issues/" + issue.key()).send(), 404, "NOT_FOUND");
        assertThat(allValues(agent.client(), "/api/console/issues", "key")).doesNotContain(issue.key());

        ApiResponse assigned = admin.client()
                .put("/api/console/companies/" + company.code() + "/agents/" + agent.accountId())
                .expect(200);

        assertThat(assigned.jsonLong("accountId")).isEqualTo(agent.accountId());
        assertThat(assigned.jsonText("status")).isEqualTo("ACTIVE");
        assertThat(agent.client().get("/api/console/issues/" + issue.key()).expect(200).jsonText("key"))
                .isEqualTo(issue.key());
        assertThat(allValues(agent.client(), "/api/console/issues", "key")).contains(issue.key());
        assertThat(allValues(agent.client(), "/api/console/companies", "code")).contains(company.code());
    }

    @Test
    @DisplayName("S0-AC-31 an agent assigned before accepting sees the company's issues once signed in")
    void invitedAgentCanBeAssigned() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Issue issue = fixtures.fileIssue(fixtures.customer(admin, company.code()), company.code());
        Invitation invitation = fixtures.invite(admin, Role.AGENT);

        ApiResponse assigned = admin.client()
                .put("/api/console/companies/" + company.code() + "/agents/" + invitation.accountId())
                .expect(200);
        assertThat(assigned.jsonLong("accountId")).isEqualTo(invitation.accountId());
        assertThat(assigned.jsonText("status")).isEqualTo("INVITED");

        Session agent = fixtures.accept(invitation);

        assertThat(agent.client().get("/api/console/issues/" + issue.key()).expect(200).jsonText("key"))
                .isEqualTo(issue.key());
        assertThat(allValues(agent.client(), "/api/console/companies", "code")).contains(company.code());
    }

    @Test
    @DisplayName("S0-AC-31 assigning the same agent again is 200 and the agent is listed once")
    void assigningAgainIsOk() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());

        ApiResponse again = admin.client()
                .put("/api/console/companies/" + company.code() + "/agents/" + agent.accountId())
                .expect(200);

        assertThat(again.jsonLong("accountId")).isEqualTo(agent.accountId());
        assertThat(allItems(admin.client(), "/api/console/companies/" + company.code() + "/agents").stream()
                .filter(n -> n.path("accountId").asLong(-1) == agent.accountId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("S0-AC-31 assigning a customer or an admin is 400 on accountId; an unknown account or company is 404")
    void assignsOnlyAgents() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session otherAdmin = fixtures.admin(admin);
        Session agent = fixtures.agent(admin);
        String agents = "/api/console/companies/" + company.code() + "/agents/";

        assertFieldError(admin.client().put(agents + customer.accountId()).send(), 400, "accountId",
                "UNKNOWN_VALUE");
        assertFieldError(admin.client().put(agents + otherAdmin.accountId()).send(), 400, "accountId",
                "UNKNOWN_VALUE");
        assertError(admin.client().put(agents + 999_999_999L).send(), 404, "NOT_FOUND");
        assertError(admin.client().put("/api/console/companies/NOSUCH9/agents/" + agent.accountId()).send(), 404,
                "NOT_FOUND");

        assertThat(allValues(admin.client(), "/api/console/companies/" + company.code() + "/agents", "email"))
                .doesNotContain(customer.email(), otherAdmin.email());
    }
}
