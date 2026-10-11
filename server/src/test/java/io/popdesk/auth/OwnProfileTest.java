package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** One's own name and language ({@code PATCH .../me}) and what {@code GET .../me} tells (ACCT-2, screens P-6, C-6). */
class OwnProfileTest extends ConnectedAppTest {

    @ParameterizedTest
    @EnumSource(App.class)
    @DisplayName("S1A-AC-16 a new name of 50 characters is saved and comes back from the next request on")
    void nameIsSaved(App app) {
        Session me = person(app);
        String name = (Unique.text("Renamed") + " " + "n".repeat(50)).substring(0, 50);

        ApiResponse saved = me.client().patch(me.api("/me")).json(Map.of("name", name)).expect(200);

        assertThat(saved.jsonText("name")).isEqualTo(name);
        ApiResponse read = me.client().get(me.api("/me")).expect(200);
        assertThat(read.jsonText("name")).isEqualTo(name);
        assertThat(read.jsonText("email")).isEqualTo(me.email());
    }

    @ParameterizedTest
    @EnumSource(App.class)
    @DisplayName("S1A-AC-16 a new language is saved, and the next mail to the account is in it")
    void languageIsSavedAndUsedForMail(App app) {
        Session me = person(app);

        ApiResponse saved = me.client().patch(me.api("/me")).json(Map.of("locale", "ko")).expect(200);

        assertThat(saved.jsonText("locale")).isEqualTo("ko");
        ApiResponse read = me.client().get(me.api("/me")).expect(200);
        assertThat(read.jsonText("locale")).isEqualTo("ko");
        assertThat(read.jsonText("name")).as("the name, which the request left out").isEqualTo(me.name());
        PasswordSteps.request(client(app), me.email()).expectStatus(202);
        ReceivedMail mail = PasswordSteps.awaitMails(mail(), me.email(), 1).getFirst();
        assertThat(PasswordSteps.hasHangul(mail.subject())).as("Korean subject: %s", mail.subject()).isTrue();
        assertThat(PasswordSteps.hasHangul(mail.text())).as("Korean text: %s", mail.text()).isTrue();
    }

    static Stream<Arguments> refusedValues() {
        return Stream.of(
                Arguments.of("name", "n".repeat(51), "TOO_LONG"),
                Arguments.of("name", "", "REQUIRED"),
                Arguments.of("locale", "fr", "UNKNOWN_VALUE"));
    }

    @ParameterizedTest
    @MethodSource("refusedValues")
    @DisplayName("S1A-AC-16 a name outside 1 to 50 characters or a language other than ko and en gets 400 and "
            + "saves nothing")
    void valueOutsideTheRulesIsRefused(String field, String value, String code) {
        Session me = person(App.PORTAL);

        ApiResponse refused = me.client().patch("/api/portal/me").json(Map.of(field, value)).send();

        assertError(refused, 400, "INVALID_INPUT");
        assertThat(AuthChecks.fieldErrors(refused)).contains(field + ":" + code);
        ApiResponse read = me.client().get("/api/portal/me").expect(200);
        assertThat(read.jsonText("name")).isEqualTo(me.name());
        assertThat(read.jsonText("locale")).isEqualTo("en");
    }

    @Test
    @DisplayName("S1A-AC-19 a customer's own details have name, email, language, role, a null photo time, and "
            + "the companies the customer belongs to")
    void customerSeesOwnDetailsAndCompanies() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, a.code(), b.code());

        JsonNode me = customer.client().get("/api/portal/me").expect(200).json();

        assertOwnDetails(me, customer, "CUSTOMER");
        List<Company> mine = Stream.of(a, b).sorted(Comparator.comparing(Company::code)).toList();
        assertThat(texts(me.path("companies"), "code")).containsExactlyElementsOf(codes(mine));
        assertThat(texts(me.path("companies"), "name")).containsExactlyElementsOf(names(mine));
        assertThat(texts(me.path("companies"), "status")).containsExactly("ACTIVE", "ACTIVE");
    }

    @Test
    @DisplayName("S1A-AC-19 an agent's own details have name, email, language, role, a null photo time, and "
            + "the companies the agent is assigned to")
    void agentSeesOwnDetailsAndAssignedCompanies() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, a.code(), b.code());

        JsonNode me = agent.client().get("/api/console/me").expect(200).json();

        assertOwnDetails(me, agent, "AGENT");
        List<Company> assigned = Stream.of(a, b).sorted(Comparator.comparing(Company::code)).toList();
        assertThat(texts(me.path("companies"), "code")).containsExactlyElementsOf(codes(assigned));
        assertThat(texts(me.path("companies"), "name")).containsExactlyElementsOf(names(assigned));
    }

    private static void assertOwnDetails(JsonNode me, Session who, String role) {
        assertThat(me.path("id").asLong(-1)).isEqualTo(who.accountId());
        assertThat(me.path("name").asString(null)).isEqualTo(who.name());
        assertThat(me.path("email").asString(null)).isEqualTo(who.email());
        assertThat(me.path("role").asString(null)).isEqualTo(role);
        assertThat(me.path("locale").asString(null)).as("locale of an account of an install without POPDESK_LOCALE")
                .isEqualTo("en");
        assertThat(me.has("photoUpdatedAt")).as("photoUpdatedAt is in %s", me).isTrue();
        assertThat(me.path("photoUpdatedAt").isNull()).as("photoUpdatedAt without a photo: %s", me).isTrue();
    }

    private Session person(App app) {
        Session admin = fixtures.signInAdmin();
        return app == App.PORTAL
                ? fixtures.customer(admin, fixtures.createCompany(admin).code())
                : fixtures.accept(fixtures.invite(admin, Role.AGENT));
    }

    private static List<String> texts(JsonNode items, String field) {
        return items.values().stream().map(item -> item.path(field).asString(null)).toList();
    }

    private static List<String> codes(List<Company> companies) {
        return companies.stream().map(Company::code).toList();
    }

    private static List<String> names(List<Company> companies) {
        return companies.stream().map(Company::name).toList();
    }
}
