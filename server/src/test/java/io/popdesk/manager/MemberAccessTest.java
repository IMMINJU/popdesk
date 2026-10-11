package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.customers;
import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.member;
import static io.popdesk.manager.ManagerSteps.members;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.resend;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rowId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Who may call the four member endpoints (list, invite, remove, send again): only a manager of the
 * company in the path, checked on every request, and only for rows of that company. These are also
 * the company boundary tests of the member endpoints.
 */
class MemberAccessTest extends BareAppTest {

    /** The four member endpoints. */
    enum Endpoint {
        LIST,
        INVITE,
        REMOVE,
        RESEND;

        /** Calls the endpoint for {@code code}; {@code rowId} and {@code invitation} are used where they apply. */
        ApiResponse call(Session caller, String code, String rowId, Map<String, Object> invitation) {
            return switch (this) {
                case LIST -> caller.client().get(members(code)).send();
                case INVITE -> caller.client().post(members(code)).json(invitation).send();
                case REMOVE -> caller.client().delete(member(code, rowId)).send();
                case RESEND -> caller.client().post(resend(code, rowId)).send();
            };
        }
    }

    private static final Map<String, Object> INVALID_INVITATION = Map.of("email", "not-an-email", "name", "");

    // S2-AC-09, S2-AC-19

    @ParameterizedTest(name = "{0}")
    @EnumSource(Endpoint.class)
    @DisplayName("S2-AC-03, S2-AC-09, S2-AC-19 a member who is no manager of the company gets 403 FORBIDDEN, "
            + "also one who is a manager of another company; a manager of another company who is no member, "
            + "or a caller of an unknown code, gets 404 NOT_FOUND; so does an invitation with invalid input; "
            + "the company's rows stay as they were")
    void onlyManagersOfTheCompany(Endpoint endpoint) {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session managerOfA = ManagerSteps.manager(fixtures, admin, a.code());
        Session memberOfA = fixtures.customer(admin, a.code());
        Session managerOfB = ManagerSteps.manager(fixtures, admin, b.code());
        Session memberOfAManagerOfB = fixtures.customer(admin, a.code(), b.code());
        mark(admin, b.code(), memberOfAManagerOfB.accountId());
        Invitation target = fixtures.invite(admin, Role.CUSTOMER, a.code());
        String targetRow = rowId(managerOfA, a.code(), target.email());
        List<String> before = emails(managerOfA, a.code());

        ApiResponse byMember = endpoint.call(memberOfA, a.code(), targetRow, INVALID_INVITATION);
        ApiResponse byManagerElsewhere = endpoint.call(memberOfAManagerOfB, a.code(), targetRow,
                INVALID_INVITATION);
        ApiResponse byOtherManager = endpoint.call(managerOfB, a.code(), targetRow, INVALID_INVITATION);
        ApiResponse unknownCode = endpoint.call(managerOfB, "NOSUCH9", targetRow, INVALID_INVITATION);

        assertError(byMember, 403, "FORBIDDEN");
        assertError(byManagerElsewhere, 403, "FORBIDDEN");
        assertError(byOtherManager, 404, "NOT_FOUND");
        assertError(unknownCode, 404, "NOT_FOUND");
        assertThat(emails(managerOfA, a.code())).isEqualTo(before);
        assertThat(row(managerOfA, a.code(), target.email()).path("status").asString(null)).isEqualTo("INVITED");
        fixtures.accept(target);
    }

    // S2-AC-11

    @ParameterizedTest(name = "{0} after the manager was {1}")
    @CsvSource({
            "LIST, unmarked, 403, FORBIDDEN", "INVITE, unmarked, 403, FORBIDDEN",
            "REMOVE, unmarked, 403, FORBIDDEN", "RESEND, unmarked, 403, FORBIDDEN",
            "LIST, removed, 404, NOT_FOUND", "INVITE, removed, 404, NOT_FOUND",
            "REMOVE, removed, 404, NOT_FOUND", "RESEND, removed, 404, NOT_FOUND"})
    @DisplayName("S2-AC-11 a manager whose mark was taken away gets 403, and one taken out of the company gets "
            + "404, from the next member request of the session they already had; nothing changes")
    void appliesFromTheNextRequest(Endpoint endpoint, String change, int status, String code) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        Invitation target = fixtures.invite(admin, Role.CUSTOMER, company.code());
        String targetRow = rowId(manager, company.code(), target.email());
        String email = Unique.email("after");
        if (change.equals("unmarked")) {
            ManagerSteps.unmark(admin, company.code(), manager.accountId());
        } else {
            ManagerSteps.removeCustomer(admin, company.code(), manager.accountId());
        }

        ApiResponse response = endpoint.call(manager, company.code(), targetRow,
                Map.of("email", email, "name", "Invited after"));

        assertError(response, status, code);
        assertThat(customers(admin, company.code())).extracting(c -> c.path("email").asString(null))
                .contains(target.email()).doesNotContain(email);
        assertThat(ManagerSteps.pendingInvitations(admin, target.email())).singleElement()
                .satisfies(p -> assertThat(p.path("cancelled").asBoolean(true)).isFalse());
        assertThat(ManagerSteps.requests(admin, company.code())).isEmpty();
        fixtures.accept(target);
    }

    // S2-AC-32

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
            "REMOVE, an unknown UUID", "REMOVE, text that is no UUID", "REMOVE, a member's account ID",
            "REMOVE, a row of another company", "REMOVE, a request row of another company",
            "RESEND, an unknown UUID", "RESEND, text that is no UUID", "RESEND, a member's account ID",
            "RESEND, a row of another company", "RESEND, a request row of another company"})
    @DisplayName("S2-AC-32 removing or sending again with a row ID that is unknown or belongs to another company "
            + "the caller also manages, a member's or a membership request's, is 404 NOT_FOUND, and that "
            + "company's member and request stay as they were")
    void rowsOfTheCompanyInThePathOnly(Endpoint endpoint, String idKind) {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session manager = fixtures.customer(admin, a.code(), b.code());
        mark(admin, a.code(), manager.accountId());
        mark(admin, b.code(), manager.accountId());
        Invitation ofA = fixtures.invite(admin, Role.CUSTOMER, a.code());
        Invitation ofB = fixtures.invite(admin, Role.CUSTOMER, b.code());
        Session elsewhere = fixtures.customer(admin, fixtures.createCompany(admin).code());
        invited(manager, b.code(), elsewhere.email(), "Requested for B");
        String rowOfB = rowId(manager, b.code(), ofB.email());
        String requestRowOfB = rowId(manager, b.code(), elsewhere.email());
        List<JsonNode> requestsOfB = requests(admin, b.code());
        assertThat(requestsOfB).as("pending requests of B").hasSize(1);
        String id = switch (idKind) {
            case "an unknown UUID" -> UUID.randomUUID().toString();
            case "text that is no UUID" -> "not-a-uuid";
            case "a member's account ID" -> Long.toString(ofA.accountId());
            case "a request row of another company" -> requestRowOfB;
            default -> rowOfB;
        };

        ApiResponse response = endpoint.call(manager, a.code(), id, Map.of());

        assertError(response, 404, "NOT_FOUND");
        assertThat(rowId(manager, b.code(), ofB.email())).isEqualTo(rowOfB);
        assertThat(rowId(manager, b.code(), elsewhere.email())).isEqualTo(requestRowOfB);
        assertThat(requests(admin, b.code())).as("pending requests of B").isEqualTo(requestsOfB);
        assertThat(customerIds(admin, a.code())).contains(ofA.accountId());
        assertThat(customerIds(admin, b.code())).contains(ofB.accountId());
        fixtures.accept(ofA);
        fixtures.accept(ofB);
    }
}
