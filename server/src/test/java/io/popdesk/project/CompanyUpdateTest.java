package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.company;
import static io.popdesk.project.PeopleSteps.setStatus;
import static io.popdesk.project.PeopleSteps.statusOf;
import static io.popdesk.project.ProjectChecks.allItems;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Changing a company's name, legal name, and status, and where the status shows. */
class CompanyUpdateTest extends BareAppTest {

    /** A company brought to {@code status} from {@code ACTIVE}. */
    private Company companyIn(Session admin, String status) {
        Company company = fixtures.createCompany(admin);
        if (!status.equals("ACTIVE")) {
            setStatus(admin, company.code(), status);
        }
        return company;
    }

    private static JsonNode listed(Session session, String path, String code) {
        return allItems(session.client(), path).stream().filter(n -> code.equals(n.path("code").asString(null)))
                .findFirst().orElseThrow(() -> new AssertionError("No company " + code + " in GET " + path));
    }

    // S1A-AC-43

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"name", "legalName"})
    @DisplayName("S1A-AC-43 an admin changes a company's name or legal name; the other one and the code stay")
    void updatesOneField(String field) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String value = Unique.text("Changed");

        ApiResponse updated = admin.client().patch(company(company.code())).json(Map.of(field, value)).expect(200);

        String name = field.equals("name") ? value : company.name();
        String legalName = field.equals("legalName") ? value : company.legalName();
        assertThat(updated.jsonText("code")).isEqualTo(company.code());
        assertThat(updated.jsonText("name")).isEqualTo(name);
        assertThat(updated.jsonText("legalName")).isEqualTo(legalName);
        ApiResponse read = admin.client().get(company(company.code())).expect(200);
        assertThat(read.jsonText("name")).isEqualTo(name);
        assertThat(read.jsonText("legalName")).isEqualTo(legalName);
        assertThat(listed(admin, "/api/console/companies", company.code()).path("name").asString(null))
                .isEqualTo(name);
    }

    @Test
    @DisplayName("S1A-AC-43 a request that carries code is 400 with field code NOT_ALLOWED and changes nothing")
    void codeCannotChange() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String newCode = Unique.companyCode();

        ApiResponse response = admin.client().patch(company(company.code()))
                .json(Map.of("code", newCode, "name", Unique.text("Changed"))).send();

        assertFieldError(response, 400, "code", "NOT_ALLOWED");
        assertThat(admin.client().get(company(company.code())).expect(200).jsonText("name"))
                .isEqualTo(company.name());
        assertError(admin.client().get(company(newCode)).send(), 404, "NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"update", "status"})
    @DisplayName("S1A-AC-43, S1A-AC-44 an agent, also one assigned to the company, gets 403 FORBIDDEN and nothing "
            + "changes")
    void agentsCannotChangeACompany(String action) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());

        ApiResponse response = action.equals("update")
                ? agent.client().patch(company(company.code())).json(Map.of("name", Unique.text("Changed"))).send()
                : agent.client().put(company(company.code()) + "/status").json(Map.of("status", "SUSPENDED")).send();

        assertError(response, 403, "FORBIDDEN");
        ApiResponse read = admin.client().get(company(company.code())).expect(200);
        assertThat(read.jsonText("name")).isEqualTo(company.name());
        assertThat(read.jsonText("status")).isEqualTo("ACTIVE");
    }

    // S1A-AC-44

    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({
            "ACTIVE, SUSPENDED", "SUSPENDED, ACTIVE", "ACTIVE, ARCHIVED", "SUSPENDED, ARCHIVED",
            "ACTIVE, ACTIVE", "SUSPENDED, SUSPENDED",
    })
    @DisplayName("S1A-AC-44 an admin moves a company between ACTIVE and SUSPENDED, or from either to ARCHIVED; the "
            + "status it already has is 200")
    void changesStatus(String from, String to) {
        Session admin = fixtures.signInAdmin();
        Company company = companyIn(admin, from);
        assertThat(statusOf(admin, company.code())).isEqualTo(from);

        ApiResponse changed = admin.client().put(company(company.code()) + "/status").json(Map.of("status", to))
                .expect(200);

        assertThat(changed.jsonText("code")).isEqualTo(company.code());
        assertThat(changed.jsonText("status")).isEqualTo(to);
        assertThat(changed.jsonText("name")).isEqualTo(company.name());
        assertThat(statusOf(admin, company.code())).isEqualTo(to);
    }

    @Test
    @DisplayName("S1A-AC-44 a status that is none of the three is 400 with field status UNKNOWN_VALUE")
    void unknownStatus() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        ApiResponse response = admin.client().put(company(company.code()) + "/status")
                .json(Map.of("status", "CLOSED")).send();

        assertFieldError(response, 400, "status", "UNKNOWN_VALUE");
        assertThat(statusOf(admin, company.code())).isEqualTo("ACTIVE");
    }

    // S1A-AC-45

    @ParameterizedTest(name = "ARCHIVED to {0}")
    @ValueSource(strings = {"ACTIVE", "SUSPENDED"})
    @DisplayName("S1A-AC-45 changing the status of an ARCHIVED company is 400 COMPANY_ARCHIVED and it stays ARCHIVED")
    void archivedIsFinal(String to) {
        Session admin = fixtures.signInAdmin();
        Company company = companyIn(admin, "ARCHIVED");

        ApiResponse response = admin.client().put(company(company.code()) + "/status").json(Map.of("status", to))
                .send();

        assertError(response, 400, "COMPANY_ARCHIVED");
        assertThat(statusOf(admin, company.code())).isEqualTo("ARCHIVED");
    }

    // S1A-AC-49

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "ARCHIVED"})
    @DisplayName("S1A-AC-49 the console list and detail give the status, and a customer's company list keeps a "
            + "company that is not ACTIVE, with its status")
    void statusInListsAndDetail(String status) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company active = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code(), active.code());
        Session agent = fixtures.agent(admin, company.code());

        setStatus(admin, company.code(), status);

        assertThat(statusOf(admin, company.code())).isEqualTo(status);
        assertThat(listed(admin, "/api/console/companies", company.code()).path("status").asString(null))
                .as("status in the admin's list").isEqualTo(status);
        assertThat(listed(agent, "/api/console/companies", company.code()).path("status").asString(null))
                .as("status in the assigned agent's list").isEqualTo(status);
        assertThat(listed(customer, "/api/portal/companies", company.code()).path("status").asString(null))
                .as("status in the customer's list").isEqualTo(status);
        assertThat(listed(customer, "/api/portal/companies", active.code()).path("status").asString(null))
                .as("status of the other company in the customer's list").isEqualTo("ACTIVE");
    }
}
