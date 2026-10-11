package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.account;
import static io.popdesk.project.PeopleSteps.company;
import static io.popdesk.project.ProjectChecks.grown;
import static io.popdesk.project.ProjectChecks.rowCounts;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An admin's {@code PATCH} of an account or a company with a field sent as {@code null}, and with a field
 * left out (ADR D-44): none of these fields can be empty, so {@code null} is refused and writes no row,
 * while a field that is not in the request stays.
 */
class AdminPatchNullFieldTest extends BareAppTest {

    /** Checks 400 {@code INVALID_INPUT} whose {@code fields} is the one entry {@code field: REQUIRED}. */
    private static void assertOnlyRequired(ApiResponse response, String field) {
        assertThat(response.status()).as(response.request() + ": " + response.snippet()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
        List<String> pairs = new ArrayList<>();
        response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                + f.path("code").asString(null)));
        assertThat(pairs).as("fields of " + response.request()).containsExactly(field + ":REQUIRED");
    }

    // S1A-AC-30

    @Test
    @DisplayName("S1A-AC-30 an account's name sent as null is 400 with field name REQUIRED, keeps the name, and "
            + "writes no row")
    void nullAccountNameIsRefused() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        Map<String, Long> before = rowCounts(db);

        ApiResponse refused = admin.client().patch(account(agent.accountId())).jsonText("{\"name\": null}").send();

        assertOnlyRequired(refused, "name");
        assertThat(ProjectChecks.account(admin, agent.accountId()).path("name").asString(null))
                .isEqualTo(agent.name());
        assertThat(grown(before, rowCounts(db))).isEmpty();
    }

    @Test
    @DisplayName("S1A-AC-30 a request without the account's name is 200 and keeps the name")
    void accountNameLeftOutStays() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);

        ApiResponse saved = admin.client().patch(account(agent.accountId())).jsonText("{}").expect(200);

        assertThat(saved.jsonLong("id")).isEqualTo(agent.accountId());
        assertThat(saved.jsonText("name")).isEqualTo(agent.name());
        assertThat(ProjectChecks.account(admin, agent.accountId()).path("name").asString(null))
                .isEqualTo(agent.name());
    }

    // S1A-AC-43

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"name", "legalName"})
    @DisplayName("S1A-AC-43 a company's name or legal name sent as null is 400 with that one field REQUIRED, keeps "
            + "both, and writes no row")
    void nullCompanyFieldIsRefused(String field) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Map<String, Long> before = rowCounts(db);

        ApiResponse refused = admin.client().patch(company(company.code())).jsonText("{\"" + field + "\": null}")
                .send();

        assertOnlyRequired(refused, field);
        ApiResponse read = admin.client().get(company(company.code())).expect(200);
        assertThat(read.jsonText("name")).isEqualTo(company.name());
        assertThat(read.jsonText("legalName")).isEqualTo(company.legalName());
        assertThat(grown(before, rowCounts(db))).isEmpty();
    }

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {"name", "legalName"})
    @DisplayName("S1A-AC-43 a request that leaves a company's name or legal name out is 200 and keeps the one left "
            + "out")
    void companyFieldLeftOutStays(String field) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String other = field.equals("name") ? "legalName" : "name";
        String value = Unique.text("Changed");

        ApiResponse saved = admin.client().patch(company(company.code())).json(Map.of(other, value)).expect(200);

        String kept = field.equals("name") ? company.name() : company.legalName();
        assertThat(saved.jsonText(other)).isEqualTo(value);
        assertThat(saved.jsonText(field)).isEqualTo(kept);
        ApiResponse read = admin.client().get(company(company.code())).expect(200);
        assertThat(read.jsonText(other)).isEqualTo(value);
        assertThat(read.jsonText(field)).isEqualTo(kept);
    }
}
