package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a 400 says about a body the app could not read into its request type (COM-3, ADR D-44):
 * nothing about fields when the body is not JSON at all, the field and {@code INVALID_FORMAT} when a
 * value has the wrong JSON type, the field and {@code UNKNOWN_VALUE} when it is outside a fixed
 * set. The bodies go to endpoints of the first slice, as an admin and as a customer.
 */
class RequestBodyErrorTest extends BareAppTest {

    private static List<String> fieldCodes(ApiResponse response) {
        List<String> pairs = new ArrayList<>();
        response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                + f.path("code").asString(null)));
        return pairs;
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\": ", "code=AB&name=N", "<company/>", "{\"code\": \"AB\",, }"})
    @DisplayName("S1A-AC-74 a body that is not JSON is 400 INVALID_INPUT without fields")
    void notJson(String body) {
        Session admin = fixtures.signInAdmin();

        ApiResponse response = admin.client().post("/api/console/companies").jsonText(body).send();

        assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(response.json().has("fields")).as("fields in %s", response.snippet()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "company   | {\"code\": \"%s\", \"name\": {\"ko\": \"N\"}, \"legalName\": \"L\"}                    | name:INVALID_FORMAT",
            "company   | {\"code\": \"%s\", \"name\": \"N\", \"legalName\": [\"L\"]}                             | legalName:INVALID_FORMAT",
            "invite    | {\"email\": \"%s@example.test\", \"name\": \"N\", \"role\": \"AGENT\", \"companyCodes\": \"AB\"} | companyCodes:INVALID_FORMAT",
            "issue     | {\"companyCode\": \"%s\", \"title\": \"T\", \"content\": \"C\", \"private\": \"maybe\"} | private:INVALID_FORMAT",
            "issue     | {\"companyCode\": \"%s\", \"title\": [\"T\"], \"content\": \"C\"}                       | title:INVALID_FORMAT",
            "invite    | {\"email\": \"%s@example.test\", \"name\": \"N\", \"role\": \"OWNER\"}                  | role:UNKNOWN_VALUE",
            "issue     | {\"companyCode\": \"%s\", \"title\": \"T\", \"content\": \"C\", \"priority\": \"ASAP\"} | priority:UNKNOWN_VALUE",
    })
    @DisplayName("S1A-AC-74 a value of the wrong JSON type is 400 INVALID_INPUT with that field INVALID_FORMAT, "
            + "and a value outside a fixed set with that field UNKNOWN_VALUE")
    void wrongTypeOrUnknownValue(String target, String body, String expected) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        ApiResponse response = switch (target) {
            case "company" -> admin.client().post("/api/console/companies")
                    .jsonText(body.formatted(Unique.companyCode())).send();
            case "invite" -> admin.client().post("/api/console/invitations")
                    .jsonText(body.formatted("wrongtype-" + Unique.number())).send();
            case "issue" -> fixtures.customer(admin, company.code()).client().post("/api/portal/issues")
                    .jsonText(body.formatted(company.code())).send();
            default -> throw new IllegalArgumentException(target);
        };

        assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCodes(response)).as("fields of %s", response.snippet()).containsExactly(expected);
    }
}
