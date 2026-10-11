package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.acceptOnPortal;
import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.invite;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.resend;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rows;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Cases of a manager's invitation and of sending again that were decided after the first tests of the
 * member list were written: control characters in the name, an email with spaces around it, and an
 * invitation an admin cancelled.
 */
class MemberDecidedCasesTest extends BareAppTest {

    private record Scene(Session admin, Company company, Company other, Session manager) {

        String code() {
            return company.code();
        }
    }

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        return new Scene(admin, company, other, ManagerSteps.manager(fixtures, admin, company.code()));
    }

    static Stream<Arguments> namesWithControlCharacters() {
        return Stream.of(
                Arguments.of("a line feed", "Line\nbreak"),
                Arguments.of("a carriage return and a line feed", "Line\r\nbreak"),
                Arguments.of("a carriage return", "Line\rbreak"),
                Arguments.of("a tab", "Tab\tname"));
    }

    // S2-AC-53

    @ParameterizedTest(name = "{0}")
    @MethodSource("namesWithControlCharacters")
    @DisplayName("S2-AC-53 a manager's invitation whose name holds a line break or a tab is 400 INVALID_INPUT "
            + "with INVALID_FORMAT on name, for a new email and for the email of an existing account, and "
            + "invites nobody")
    void controlCharacterInTheNameIsRefused(String what, String name) {
        Scene s = scene();
        Session existing = fixtures.customer(s.admin(), s.other().code());
        List<JsonNode> rowsBefore = rows(s.manager(), s.code());

        for (String email : List.of(Unique.email("new"), existing.email())) {
            ApiResponse response = invite(s.manager(), s.code(), email, name);

            assertError(response, 400, "INVALID_INPUT");
            List<String> pairs = new ArrayList<>();
            response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                    + f.path("code").asString(null)));
            assertThat(pairs).as("fields of " + response.request()).contains("name:INVALID_FORMAT");
        }
        assertThat(rows(s.manager(), s.code())).isEqualTo(rowsBefore);
        assertThat(requests(s.admin(), s.code())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namesWithControlCharacters")
    @DisplayName("S2-AC-53 an admin's invitation still takes a name with a line break or a tab: 201")
    void adminInvitationStillTakesTheName(String what, String name) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, Unique.email("by-admin"), name,
                List.of(company.code()));

        assertThat(pendingInvitations(admin, invitation.email())).singleElement()
                .satisfies(p -> assertThat(p.path("accountId").asLong(-1)).isEqualTo(invitation.accountId()));
    }

    // S2-AC-17

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a member", "a pending membership request"})
    @DisplayName("S2-AC-17 inviting an email that is already a row, typed with spaces before and after it, is "
            + "409 ALREADY_LISTED and changes nothing")
    void listedEmailWithSpacesAround(String what) {
        Scene s = scene();
        String email;
        if (what.equals("a member")) {
            email = fixtures.customer(s.admin(), s.code()).email();
        } else {
            email = fixtures.customer(s.admin(), s.other().code()).email();
            invited(s.manager(), s.code(), email, "Requested");
        }
        List<JsonNode> rowsBefore = rows(s.manager(), s.code());
        List<JsonNode> requestsBefore = requests(s.admin(), s.code());

        ApiResponse response = invite(s.manager(), s.code(), "  " + email + " ", "Again");

        assertError(response, 409, "ALREADY_LISTED");
        assertThat(rows(s.manager(), s.code())).isEqualTo(rowsBefore);
        assertThat(requests(s.admin(), s.code())).isEqualTo(requestsBefore);
    }

    // S2-AC-31

    @Test
    @DisplayName("S2-AC-31 sending again for a member whose invitation an admin cancelled (SUSPENDED in the "
            + "list) is 400 INVITATION_NOT_RESENDABLE, and the invitation stays cancelled")
    void cancelledInvitationIsNotResendable() {
        Scene s = scene();
        Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        s.admin().client().post("/api/console/invitations/" + invitation.accountId() + "/cancel").expect(204);
        JsonNode listed = row(s.manager(), s.code(), invitation.email());
        assertThat(listed.path("status").asString(null)).as("status of the row of a cancelled invitation")
                .isEqualTo("SUSPENDED");

        ApiResponse response = s.manager().client().post(resend(s.code(), listed.path("id").asString(""))).send();

        assertError(response, 400, "INVITATION_NOT_RESENDABLE");
        assertThat(row(s.manager(), s.code(), invitation.email())).isEqualTo(listed);
        assertThat(pendingInvitations(s.admin(), invitation.email())).singleElement()
                .satisfies(p -> assertThat(p.path("cancelled").asBoolean(false)).isTrue());
        assertError(acceptOnPortal(portal(), invitation.token()), 400, "INVITATION_INVALID");
    }
}
