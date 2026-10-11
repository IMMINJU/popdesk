package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.agentOf;
import static io.popdesk.project.PeopleSteps.company;
import static io.popdesk.project.PeopleSteps.customerOf;
import static io.popdesk.project.PeopleSteps.members;
import static io.popdesk.project.PeopleSteps.setStatus;
import static io.popdesk.project.ProjectChecks.allItems;
import static io.popdesk.project.ProjectChecks.allValues;
import static io.popdesk.project.ProjectChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a company that is {@code SUSPENDED} or {@code ARCHIVED} still takes, and what it refuses. */
class CompanyNotActiveTest extends BareAppTest {

    // S1A-AC-46

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "ARCHIVED"})
    @DisplayName("S1A-AC-46 filing an issue in a company that is not ACTIVE is 400 COMPANY_NOT_ACTIVE and files "
            + "nothing")
    void noNewIssues(String status) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        setStatus(admin, company.code(), status);
        String title = Unique.text("Not filed");

        ApiResponse response = customer.client().post("/api/portal/issues")
                .json(Map.of("companyCode", company.code(), "title", title, "content", "Steps to reproduce."))
                .send();

        assertError(response, 400, "COMPANY_NOT_ACTIVE");
        assertThat(allValues(customer.client(), "/api/portal/issues", "title")).doesNotContain(title);
        assertThat(allValues(admin.client(), "/api/console/issues", "title")).doesNotContain(title);
    }

    // S1A-AC-47

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "ARCHIVED"})
    @DisplayName("S1A-AC-47 an issue filed before the company stopped being ACTIVE is still read, commented on, and "
            + "given files, by the customer and by the agent")
    void existingIssuesGoOn(String status) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        Comment before = fixtures.comment(customer, issue.key(), "Before the change");
        Attachment file = fixtures.attach(customer, issue.key(), File.text("before.txt", "before the change"));

        setStatus(admin, company.code(), status);

        String portalIssue = "/api/portal/issues/" + issue.key();
        String consoleIssue = "/api/console/issues/" + issue.key();
        assertThat(customer.client().get(portalIssue).expect(200).jsonText("key")).isEqualTo(issue.key());
        assertThat(allValues(customer.client(), "/api/portal/issues", "key")).contains(issue.key());
        assertThat(agent.client().get(consoleIssue).expect(200).jsonText("key")).isEqualTo(issue.key());
        assertThat(customer.client().get(portalIssue + "/attachments/" + file.id()).expect(200).body())
                .isEqualTo(file.content());
        Comment byCustomer = fixtures.comment(customer, issue.key(), "After the change, by the customer");
        Comment byAgent = fixtures.comment(agent, issue.key(), "After the change, by the agent");
        Attachment after = fixtures.attach(customer, issue.key(), File.text("after.txt", "after the change"));
        assertThat(allItems(customer.client(), portalIssue + "/comments").stream().map(n -> n.path("id").asLong(-1)))
                .contains(before.id(), byCustomer.id(), byAgent.id());
        assertThat(allValues(agent.client(), consoleIssue + "/attachments", "id")).contains(file.id(), after.id());
        assertThat(agent.client().get(consoleIssue + "/attachments/" + after.id()).expect(200).body())
                .isEqualTo(after.content());
    }

    // S1A-AC-48

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"customers", "agents"})
    @DisplayName("S1A-AC-48 adding a customer or an agent to an ARCHIVED company is 400 COMPANY_NOT_ACTIVE and adds "
            + "nobody")
    void archivedTakesNoNewMembers(String kind) {
        Session admin = fixtures.signInAdmin();
        Company archived = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Invitation person = kind.equals("customers")
                ? fixtures.invite(admin, Role.CUSTOMER, other.code())
                : fixtures.invite(admin, Role.AGENT);
        setStatus(admin, archived.code(), "ARCHIVED");

        ApiResponse response = admin.client().put(company(archived.code()) + "/" + kind + "/" + person.accountId())
                .send();

        assertError(response, 400, "COMPANY_NOT_ACTIVE");
        assertThat(members(admin, archived.code(), kind)).doesNotContain(person.accountId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"name", "legalName"})
    @DisplayName("S1A-AC-48 changing the name or legal name of an ARCHIVED company is 400 COMPANY_ARCHIVED and keeps "
            + "them")
    void archivedKeepsItsNames(String field) {
        Session admin = fixtures.signInAdmin();
        Company archived = fixtures.createCompany(admin);
        setStatus(admin, archived.code(), "ARCHIVED");

        ApiResponse response = admin.client().patch(company(archived.code()))
                .json(Map.of(field, Unique.text("Changed"))).send();

        assertError(response, 400, "COMPANY_ARCHIVED");
        ApiResponse read = admin.client().get(company(archived.code())).expect(200);
        assertThat(read.jsonText("name")).isEqualTo(archived.name());
        assertThat(read.jsonText("legalName")).isEqualTo(archived.legalName());
    }

    @Test
    @DisplayName("S1A-AC-48 a customer and an agent can be taken out of an ARCHIVED company")
    void archivedLetsMembersGo() {
        Session admin = fixtures.signInAdmin();
        Company archived = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, archived.code());
        Session agent = fixtures.agent(admin, archived.code());
        setStatus(admin, archived.code(), "ARCHIVED");
        assertThat(members(admin, archived.code(), "customers")).contains(customer.accountId());
        assertThat(members(admin, archived.code(), "agents")).contains(agent.accountId());

        admin.client().delete(customerOf(archived.code(), customer.accountId())).expect(200);
        admin.client().delete(agentOf(archived.code(), agent.accountId())).expect(200);

        assertThat(members(admin, archived.code(), "customers")).doesNotContain(customer.accountId());
        assertThat(members(admin, archived.code(), "agents")).doesNotContain(agent.accountId());
    }

    @Test
    @DisplayName("S1A-AC-48 a SUSPENDED company still takes a new customer and a new agent")
    void suspendedTakesNewMembers() {
        Session admin = fixtures.signInAdmin();
        Company suspended = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Invitation customer = fixtures.invite(admin, Role.CUSTOMER, other.code());
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        setStatus(admin, suspended.code(), "SUSPENDED");

        admin.client().put(customerOf(suspended.code(), customer.accountId())).expect(200);
        admin.client().put(agentOf(suspended.code(), agent.accountId())).expect(200);

        assertThat(members(admin, suspended.code(), "customers")).contains(customer.accountId());
        assertThat(members(admin, suspended.code(), "agents")).contains(agent.accountId());
    }
}
