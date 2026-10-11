package io.popdesk.auth;

import static io.popdesk.auth.AuthChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code PATCH .../me} with a field sent as {@code null}, and with a field left out (ADR D-44): name and
 * language cannot be empty, so {@code null} is refused, while a field that is not in the request stays.
 */
class OwnProfileNullFieldTest extends BareAppTest {

    static Stream<Arguments> fields() {
        return Stream.of(
                Arguments.of(App.PORTAL, "name"),
                Arguments.of(App.PORTAL, "locale"),
                Arguments.of(App.CONSOLE, "name"),
                Arguments.of(App.CONSOLE, "locale"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("fields")
    @DisplayName("S1A-AC-16 a name or a language sent as null is 400 with that one field REQUIRED and saves nothing")
    void nullIsRefused(App app, String field) {
        Session me = person(app);

        ApiResponse refused = me.client().patch(me.api("/me")).jsonText("{\"" + field + "\": null}").send();

        assertError(refused, 400, "INVALID_INPUT");
        assertThat(AuthChecks.fieldErrors(refused)).containsExactly(field + ":REQUIRED");
        ApiResponse read = me.client().get(me.api("/me")).expect(200);
        assertThat(read.jsonText("name")).isEqualTo(me.name());
        assertThat(read.jsonText("locale")).isEqualTo("en");
    }

    @ParameterizedTest(name = "{0} without {1}")
    @MethodSource("fields")
    @DisplayName("S1A-AC-16 a request that leaves the name or the language out is 200 and keeps the one left out")
    void fieldLeftOutStays(App app, String field) {
        Session me = person(app);
        String other = field.equals("name") ? "locale" : "name";
        String value = other.equals("name") ? Unique.text("Renamed") : "ko";

        ApiResponse saved = me.client().patch(me.api("/me")).json(Map.of(other, value)).expect(200);

        String kept = field.equals("name") ? me.name() : "en";
        assertThat(saved.jsonText(other)).isEqualTo(value);
        assertThat(saved.jsonText(field)).isEqualTo(kept);
        ApiResponse read = me.client().get(me.api("/me")).expect(200);
        assertThat(read.jsonText(other)).isEqualTo(value);
        assertThat(read.jsonText(field)).isEqualTo(kept);
    }

    private Session person(App app) {
        Session admin = fixtures.signInAdmin();
        return app == App.PORTAL
                ? fixtures.customer(admin, fixtures.createCompany(admin).code())
                : fixtures.accept(fixtures.invite(admin, Role.AGENT));
    }
}
