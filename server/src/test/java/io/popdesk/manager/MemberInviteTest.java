package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.assertSameResponse;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.invite;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.members;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rows;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Existing;
import io.popdesk.manager.ManagerSteps.ExistingKind;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * A manager invites to their company without SMTP: what the answer shows and hides, and when the
 * invitation is refused. The mail side is in {@link MemberMailTest}.
 */
class MemberInviteTest extends BareAppTest {

    private record Scene(Session admin, Company company, Company other, Session manager) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        return new Scene(admin, company, other, ManagerSteps.manager(fixtures, admin, company.code()));
    }

    // S2-AC-15

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExistingKind.class)
    @DisplayName("S2-AC-15 inviting the email of an existing account answers as inviting a new email does: the "
            + "same status, body, and headers, with no account ID and no link")
    void sameAnswerForNewAndExistingEmail(ExistingKind kind) {
        Scene s = scene();
        Existing existing = kind.make(fixtures, s.admin(), s.other().code());

        ApiResponse forNew = invite(s.manager(), s.company().code(), Unique.email("new"), "Same name");
        ApiResponse forExisting = invite(s.manager(), s.company().code(), existing.email(), "Same name");

        assertThat(forNew.status()).as(forNew.request() + ": " + forNew.snippet()).isEqualTo(204);
        assertThat(forNew.body()).as("body of the 204").isEmpty();
        assertSameResponse(forNew, forExisting);
    }

    // S2-AC-17

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a member", "a new account the manager just invited", "a pending membership request"})
    @DisplayName("S2-AC-17 inviting an email that is already a row of the company's member list, in any letter "
            + "case, is 409 ALREADY_LISTED and changes nothing")
    void emailAlreadyListed(String listedAs) {
        Scene s = scene();
        String email = switch (listedAs) {
            case "a member" -> fixtures.customer(s.admin(), s.company().code()).email();
            case "a new account the manager just invited" -> {
                String fresh = Unique.email("fresh");
                invited(s.manager(), s.company().code(), fresh, "Fresh");
                yield fresh;
            }
            default -> {
                Existing elsewhere = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(),
                        s.other().code());
                invited(s.manager(), s.company().code(), elsewhere.email(), "Requested");
                yield elsewhere.email();
            }
        };
        List<JsonNode> rowsBefore = rows(s.manager(), s.company().code());
        List<JsonNode> requestsBefore = requests(s.admin(), s.company().code());
        List<Long> customersBefore = customerIds(s.admin(), s.company().code());

        ApiResponse same = invite(s.manager(), s.company().code(), email, "Again");
        ApiResponse otherCase = invite(s.manager(), s.company().code(), email.toUpperCase(Locale.ROOT), "Again");

        assertError(same, 409, "ALREADY_LISTED");
        assertError(otherCase, 409, "ALREADY_LISTED");
        assertThat(rows(s.manager(), s.company().code())).isEqualTo(rowsBefore);
        assertThat(requests(s.admin(), s.company().code())).isEqualTo(requestsBefore);
        assertThat(customerIds(s.admin(), s.company().code())).isEqualTo(customersBefore);
    }

    // S2-AC-18

    @Test
    @DisplayName("S2-AC-18 inviting to an ARCHIVED company is 400 COMPANY_NOT_ACTIVE for a new and for an "
            + "existing email, and adds no row")
    void archivedCompanyTakesNoInvitation() {
        Scene s = scene();
        Existing existing = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        ManagerSteps.setCompanyStatus(s.admin(), s.company().code(), "ARCHIVED");

        ApiResponse forNew = invite(s.manager(), s.company().code(), Unique.email("new"), "New");
        ApiResponse forExisting = invite(s.manager(), s.company().code(), existing.email(), "Existing");

        assertError(forNew, 400, "COMPANY_NOT_ACTIVE");
        assertSameResponse(forNew, forExisting);
        assertThat(emails(s.manager(), s.company().code())).containsExactly(s.manager().email());
        assertThat(requests(s.admin(), s.company().code())).isEmpty();
    }

    @Test
    @DisplayName("S2-AC-18 a manager can invite to a SUSPENDED company")
    void suspendedCompanyTakesInvitations() {
        Scene s = scene();
        String email = Unique.email("new");
        ManagerSteps.setCompanyStatus(s.admin(), s.company().code(), "SUSPENDED");

        ApiResponse response = invite(s.manager(), s.company().code(), email, "New");

        response.expectStatus(204);
        assertThat(row(s.manager(), s.company().code(), email).path("status").asString(null)).isEqualTo("INVITED");
    }

    // S2-AC-19

    static Stream<Arguments> invalidInput() {
        return Stream.of(
                Arguments.of("no email", "", "Name", "email", "REQUIRED"),
                Arguments.of("an email without @", "not-an-email", "Name", "email", "INVALID_FORMAT"),
                Arguments.of("an email with a space", "two words@example.test", "Name", "email", "INVALID_FORMAT"),
                Arguments.of("an email of 101 characters", "a".repeat(88) + "@example.test", "Name", "email",
                        "TOO_LONG"),
                Arguments.of("no name", null, "", "name", "REQUIRED"),
                Arguments.of("a name of only spaces", null, "   ", "name", "BLANK"),
                Arguments.of("a name of 51 characters", null, "n".repeat(51), "name", "TOO_LONG"),
                Arguments.of("a name with a NUL character", null, "Na\u0000me", "name", "INVALID_FORMAT"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInput")
    @DisplayName("S2-AC-19 an invitation whose email or name breaks the rules of the admin's invitation is 400 "
            + "INVALID_INPUT naming the field, before the email is looked up: also for the email of a member "
            + "and of an existing account")
    void invalidInputIsRefusedFirst(String what, String email, String name, String field, String fieldCode) {
        Scene s = scene();
        Session member = fixtures.customer(s.admin(), s.company().code());
        Existing existing = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        List<String> emails = email != null ? List.of(email) : List.of(member.email(), existing.email());
        List<JsonNode> rowsBefore = rows(s.manager(), s.company().code());

        List<ApiResponse> responses = new ArrayList<>();
        for (String sent : emails) {
            responses.add(invite(s.manager(), s.company().code(), sent, name));
        }

        for (ApiResponse response : responses) {
            assertError(response, 400, "INVALID_INPUT");
            List<String> pairs = new ArrayList<>();
            response.json().path("fields").forEach(f -> pairs.add(f.path("field").asString(null) + ":"
                    + f.path("code").asString(null)));
            assertThat(pairs).as("fields of " + response.request()).contains(field + ":" + fieldCode);
        }
        assertSameResponse(responses.getFirst(), responses.getLast());
        assertThat(rows(s.manager(), s.company().code())).isEqualTo(rowsBefore);
        assertThat(requests(s.admin(), s.company().code())).isEmpty();
    }

    // S2-AC-21

    @Test
    @DisplayName("S2-AC-21 with 100 customers who have not accepted and pending membership requests together, "
            + "another invitation is 409 TOO_MANY_PENDING for a new and for an existing email; the 100th still "
            + "goes through")
    void atMostHundredPending() {
        Scene s = scene();
        String code = s.company().code();
        Existing first = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        Existing second = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        for (int i = 0; i < 97; i++) {
            fixtures.invite(s.admin(), Role.CUSTOMER, code);
        }
        invited(s.manager(), code, first.email(), "Request 98");
        invited(s.manager(), code, Unique.email("pending"), "Pending 99");
        String hundredth = Unique.email("hundredth");
        String overNew = Unique.email("over");

        ApiResponse atNinetyNine = invite(s.manager(), code, hundredth, "Pending 100");
        ApiResponse atHundredNew = invite(s.manager(), code, overNew, "Pending 101");
        ApiResponse atHundredExisting = invite(s.manager(), code, second.email(), "Request 101");

        atNinetyNine.expectStatus(204);
        assertError(atHundredNew, 409, "TOO_MANY_PENDING");
        assertSameResponse(atHundredNew, atHundredExisting);
        List<String> listed = emails(s.manager(), code);
        assertThat(listed).hasSize(101).contains(hundredth, first.email(), s.manager().email())
                .doesNotContain(overNew, second.email());
        assertThat(requests(s.admin(), code)).hasSize(1);
    }

    // S2-AC-22

    @Test
    @DisplayName("S2-AC-22 a role and companies sent with a manager's invitation are not taken: the account is a "
            + "customer of the company in the path only")
    void noRoleOrCompanyChoice() {
        Scene s = scene();
        String email = Unique.email("no-choice");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", "No choice");
        body.put("role", "ADMIN");
        body.put("companyCodes", List.of(s.other().code()));
        body.put("companyCode", s.other().code());

        s.manager().client().post(members(s.company().code())).json(body).expect(204);

        assertThat(pendingInvitations(s.admin(), email)).singleElement().satisfies(p -> {
            assertThat(p.path("role").asString(null)).isEqualTo("CUSTOMER");
            assertThat(p.path("companies")).extracting(c -> c.path("code").asString(null))
                    .containsExactly(s.company().code());
        });
        assertThat(ManagerSteps.customers(s.admin(), s.other().code()))
                .extracting(c -> c.path("email").asString(null)).doesNotContain(email);
    }

    // S2-AC-23

    @Test
    @DisplayName("S2-AC-23 without SMTP the manager's invitation is still made and answers 204 with no body: no "
            + "link, although the admin's invitation carries one")
    void noLinkWithoutSmtp() {
        Scene s = scene();
        String email = Unique.email("no-link");

        ApiResponse response = invite(s.manager(), s.company().code(), email, "No link");

        response.expectStatus(204);
        assertThat(response.body()).as("body of the 204").isEmpty();
        assertThat(response.header("Location")).as("Location").isNull();
        assertThat(row(s.manager(), s.company().code(), email).path("status").asString(null)).isEqualTo("INVITED");
        assertThat(pendingInvitations(s.admin(), email)).singleElement().satisfies(p -> {
            assertThat(p.path("cancelled").asBoolean(true)).isFalse();
            assertThat(p.path("role").asString(null)).isEqualTo("CUSTOMER");
        });
    }
}
