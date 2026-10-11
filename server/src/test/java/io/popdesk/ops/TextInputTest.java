package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Text that cannot be stored or shown: NUL (U+0000) and a surrogate without its pair (conventions
 * "Text input"). The bad character is written in the JSON as a backslash-u escape, the only way a
 * lone surrogate can travel in a UTF-8 body.
 */
class TextInputTest extends BareAppTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Stands in the request map where the JSON escape goes. */
    private static final String PLACE = "@@BAD@@";

    static Stream<Arguments> fieldsAndCharacters() {
        Map<String, String> escapes = new LinkedHashMap<>();
        escapes.put("NUL", "\\u0000");
        escapes.put("a high surrogate alone", "\\ud83d");
        escapes.put("a low surrogate alone", "\\ude00");
        List<String> fields = List.of("issue title", "issue content", "comment", "company name", "invited name",
                "own name", "account name");
        List<Arguments> out = new ArrayList<>();
        for (String field : fields) {
            escapes.forEach((what, escape) -> out.add(arguments(field, named(what, escape))));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("fieldsAndCharacters")
    @DisplayName("S1A-AC-67 NUL or a surrogate without its pair in a text field is 400 INVALID_INPUT with that "
            + "field INVALID_FORMAT")
    void unstorableCharacterIsRefused(String field, String escape) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        String text = "Before " + PLACE + " after " + Unique.number();

        ApiResponse response = switch (field) {
            case "issue title" -> send(customer, "POST", "/api/portal/issues",
                    Map.of("companyCode", company.code(), "title", text, "content", "Steps."), escape);
            case "issue content" -> send(customer, "POST", "/api/portal/issues",
                    Map.of("companyCode", company.code(), "title", Unique.text("Issue"), "content", text), escape);
            case "comment" -> {
                Issue issue = fixtures.fileIssue(customer, company.code());
                yield send(customer, "POST", "/api/portal/issues/" + issue.key() + "/comments",
                        Map.of("content", text), escape);
            }
            case "company name" -> send(admin, "POST", "/api/console/companies",
                    Map.of("code", Unique.companyCode(), "name", text, "legalName", "Legal name"), escape);
            case "invited name" -> send(admin, "POST", "/api/console/invitations",
                    Map.of("email", Unique.email("badname"), "name", text, "role", Role.AGENT.name()), escape);
            case "own name" -> send(customer, "PATCH", "/api/portal/me", Map.of("name", text), escape);
            case "account name" -> send(admin, "PATCH", "/api/console/accounts/" + customer.accountId(),
                    Map.of("name", text), escape);
            default -> throw new IllegalArgumentException(field);
        };

        String expected = switch (field) {
            case "issue title" -> "title";
            case "issue content", "comment" -> "content";
            default -> "name";
        };
        assertThat(response.status()).as("%s: %s", response, response.snippet()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
        List<String> pairs = new ArrayList<>();
        response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                + f.path("code").asString(null)));
        assertThat(pairs).as("fields of %s", response.snippet()).containsExactly(expected + ":INVALID_FORMAT");
    }

    @Test
    @DisplayName("S1A-AC-67 a surrogate pair (a character outside the basic plane) is not refused: the title is "
            + "stored as sent")
    void surrogatePairIsStored() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        long number = Unique.number();
        String title = "Printer 😀 jams " + number;

        ApiResponse filed = send(customer, "POST", "/api/portal/issues", Map.of("companyCode", company.code(),
                "title", "Printer " + PLACE + " jams " + number, "content", "Steps."), "\\ud83d\\ude00");

        assertThat(filed.status()).as("%s: %s", filed, filed.snippet()).isEqualTo(201);
        assertThat(customer.client().get("/api/portal/issues/" + filed.jsonText("key")).expect(200).jsonText("title"))
                .isEqualTo(title);
    }

    /** Sends {@code body} as JSON with {@code escape} written where {@link #PLACE} stands. */
    private static ApiResponse send(Session session, String method, String path, Map<String, Object> body,
            String escape) {
        String json = JSON.writeValueAsString(body);
        assertThat(json).as("the request holds the place for the escape").contains(PLACE);
        return session.client().request(method, path).jsonText(json.replace(PLACE, escape)).send();
    }
}
