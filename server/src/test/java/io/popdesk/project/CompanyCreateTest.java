package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Creating a customer company: field limits, the code rule, and a taken code. */
class CompanyCreateTest extends BareAppTest {

    private static final String COMPANIES = "/api/console/companies";

    @Test
    @DisplayName("S0-AC-29 an admin creates an ACTIVE company and reads it back")
    void createsActiveCompany() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();

        ApiResponse created = create(admin, code, "Acme " + code, "Acme Trading " + code + " Ltd.");

        created.expectStatus(201);
        assertThat(created.jsonText("code")).isEqualTo(code);
        assertThat(created.jsonText("name")).isEqualTo("Acme " + code);
        assertThat(created.jsonText("legalName")).isEqualTo("Acme Trading " + code + " Ltd.");
        assertThat(created.jsonText("status")).isEqualTo("ACTIVE");
        assertThat(created.jsonText("createdAt")).endsWith("Z");

        JsonNode read = admin.client().get(COMPANIES + "/" + code).expect(200).json();
        assertThat(read.path("code").asString(null)).isEqualTo(code);
        assertThat(read.path("name").asString(null)).isEqualTo("Acme " + code);
        assertThat(read.path("legalName").asString(null)).isEqualTo("Acme Trading " + code + " Ltd.");
        assertThat(read.path("status").asString(null)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("S0-AC-29 name and legal name of 1 and 100 characters are accepted")
    void acceptsNamesAtTheLimits() {
        Session admin = fixtures.signInAdmin();
        String oneCode = Unique.companyCode();
        String hundredCode = Unique.companyCode();
        String hundred = "가".repeat(100);

        create(admin, oneCode, "A", "B").expectStatus(201);
        create(admin, hundredCode, hundred, hundred).expectStatus(201);

        JsonNode one = admin.client().get(COMPANIES + "/" + oneCode).expect(200).json();
        assertThat(one.path("name").asString(null)).isEqualTo("A");
        assertThat(one.path("legalName").asString(null)).isEqualTo("B");
        JsonNode full = admin.client().get(COMPANIES + "/" + hundredCode).expect(200).json();
        assertThat(full.path("name").asString(null)).isEqualTo(hundred);
        assertThat(full.path("legalName").asString(null)).isEqualTo(hundred);
    }

    @Test
    @DisplayName("S0-AC-29 lengths count code points: a name of 100 emoji is accepted")
    void countsCodePoints() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();
        String emoji = "😀".repeat(100);

        create(admin, code, emoji, emoji).expectStatus(201);

        assertThat(admin.client().get(COMPANIES + "/" + code).expect(200).json().path("name").asString(null))
                .isEqualTo(emoji);
    }

    @Test
    @DisplayName("S0-AC-29 an empty name or legal name is 400 and creates nothing")
    void rejectsEmptyNames() {
        Session admin = fixtures.signInAdmin();
        String noName = Unique.companyCode();
        String noLegalName = Unique.companyCode();

        assertFieldError(create(admin, noName, "", "Legal"), 400, "name", "REQUIRED");
        assertFieldError(create(admin, noLegalName, "Name", ""), 400, "legalName", "REQUIRED");

        assertError(admin.client().get(COMPANIES + "/" + noName).send(), 404, "NOT_FOUND");
        assertError(admin.client().get(COMPANIES + "/" + noLegalName).send(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-29 a name or legal name of 101 characters is 400 and creates nothing")
    void rejectsNamesOverTheLimit() {
        Session admin = fixtures.signInAdmin();
        String longName = Unique.companyCode();
        String longLegalName = Unique.companyCode();
        String over = "가".repeat(101);

        assertFieldError(create(admin, longName, over, "Legal"), 400, "name", "TOO_LONG");
        assertFieldError(create(admin, longLegalName, "Name", over), 400, "legalName", "TOO_LONG");

        assertError(admin.client().get(COMPANIES + "/" + longName).send(), 404, "NOT_FOUND");
        assertError(admin.client().get(COMPANIES + "/" + longLegalName).send(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-29 a name of only whitespace is 400")
    void rejectsBlankName() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();

        ApiResponse response = create(admin, code, "   ", "Legal");

        assertError(response, 400, "INVALID_INPUT");
        assertThat(response.json().path("fields").findValuesAsString("field")).contains("name");
        assertError(admin.client().get(COMPANIES + "/" + code).send(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-29 codes of 2 and 10 capital letters and digits are accepted")
    void acceptsCodesAtTheLimits() {
        Session admin = fixtures.signInAdmin();
        long n = Unique.number();
        String two = "9" + Long.toString(n % 36, 36).toUpperCase(Locale.ROOT);
        String ten = "Q" + padded(n, 9);

        create(admin, two, "Two", "Two Ltd.").expectStatus(201);
        create(admin, ten, "Ten", "Ten Ltd.").expectStatus(201);

        assertThat(admin.client().get(COMPANIES + "/" + two).expect(200).jsonText("code")).isEqualTo(two);
        assertThat(admin.client().get(COMPANIES + "/" + ten).expect(200).jsonText("code")).isEqualTo(ten);
    }

    @Test
    @DisplayName("S0-AC-29 codes of 1 or 11 characters are 400")
    void rejectsCodesOfWrongLength() {
        Session admin = fixtures.signInAdmin();
        String eleven = "Q" + padded(Unique.number(), 10);

        assertFieldError(create(admin, "Q", "One", "One Ltd."), 400, "code", "INVALID_FORMAT");
        assertFieldError(create(admin, eleven, "Eleven", "Eleven Ltd."), 400, "code", "INVALID_FORMAT");

        assertError(admin.client().get(COMPANIES + "/" + eleven).send(), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-29 codes with small letters or other characters are 400, not changed to capitals")
    void rejectsCodesThatAreNotCapitalsAndDigits() {
        Session admin = fixtures.signInAdmin();
        String lower = Unique.companyCode().toLowerCase(Locale.ROOT);
        String mixed = "Qa" + padded(Unique.number(), 4);

        assertFieldError(create(admin, lower, "Lower", "Lower Ltd."), 400, "code", "INVALID_FORMAT");
        assertFieldError(create(admin, mixed, "Mixed", "Mixed Ltd."), 400, "code", "INVALID_FORMAT");
        assertFieldError(create(admin, "Q-" + padded(Unique.number(), 4), "Dash", "Dash Ltd."), 400, "code",
                "INVALID_FORMAT");
        assertFieldError(create(admin, "가나다", "Hangul", "Hangul Ltd."), 400, "code", "INVALID_FORMAT");

        assertError(admin.client().get(COMPANIES + "/" + lower.toUpperCase(Locale.ROOT)).send(), 404,
                "NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-29 a code another company has is 409 COMPANY_CODE_TAKEN and leaves that company as it was")
    void rejectsTakenCode() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();
        create(admin, code, "First " + code, "First Ltd.").expectStatus(201);

        ApiResponse again = create(admin, code, "Second " + code, "Second Ltd.");

        assertError(again, 409, "COMPANY_CODE_TAKEN");
        JsonNode read = admin.client().get(COMPANIES + "/" + code).expect(200).json();
        assertThat(read.path("name").asString(null)).isEqualTo("First " + code);
        assertThat(read.path("legalName").asString(null)).isEqualTo("First Ltd.");
    }

    @Test
    @DisplayName("S0-AC-29 the small-letter form of a taken code is 400, not 409")
    void smallLetterFormOfTakenCodeIsInvalid() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();
        create(admin, code, "Taken", "Taken Ltd.").expectStatus(201);

        assertFieldError(create(admin, code.toLowerCase(Locale.ROOT), "Again", "Again Ltd."), 400, "code",
                "INVALID_FORMAT");
    }

    private static ApiResponse create(Session admin, String code, String name, String legalName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("legalName", legalName);
        return admin.client().post(COMPANIES).json(body).send();
    }

    /** {@code n} in base 36, capitals, left-padded with zeros to {@code width}. */
    private static String padded(long n, int width) {
        String s = Long.toString(n, 36).toUpperCase(Locale.ROOT);
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }
}
