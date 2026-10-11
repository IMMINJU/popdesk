package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.acceptOnPortal;
import static io.popdesk.manager.ManagerSteps.addCustomer;
import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.customer;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.fieldErrors;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.managerFlag;
import static io.popdesk.manager.ManagerSteps.myCompanies;
import static io.popdesk.manager.ManagerSteps.pages;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rowId;
import static io.popdesk.manager.ManagerSteps.setCompanyStatus;
import static io.popdesk.manager.RequestSteps.REQUESTS;
import static io.popdesk.manager.RequestSteps.approve;
import static io.popdesk.manager.RequestSteps.pendingEmails;
import static io.popdesk.manager.RequestSteps.pendingId;
import static io.popdesk.manager.RequestSteps.reject;
import static io.popdesk.manager.RequestSteps.request;
import static io.popdesk.manager.RequestSteps.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Existing;
import io.popdesk.manager.ManagerSteps.ExistingKind;
import io.popdesk.manager.ManagerSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The admin's side of membership requests: the list, approving, rejecting, what a closed request
 * answers, and a request closed by adding the customer on the console. Notifications and mail are in
 * {@link MembershipRequestMailTest}.
 *
 * <p>Every test makes its own companies and reads the admin's list narrowed to them.
 */
class MembershipRequestTest extends BareAppTest {

    private Scene scene() {
        return ManagerSteps.scene(fixtures);
    }

    /** A request of the scene's manager for a new customer of the other company; the customer is signed in. */
    private record Asked(Session target, long requestId, String typedName) {}

    private Asked ask(Scene s) {
        Session target = fixtures.customer(s.admin(), s.other().code());
        String typedName = Unique.text("Typed");
        return new Asked(target, request(s.admin(), s.manager(), s.code(), target.email(), typedName), typedName);
    }

    private static int pendingCount(Session staff, String code) {
        List<JsonNode> found = pages(staff.client(), "/api/console/companies").stream()
                .filter(c -> code.equals(c.path("code").asString(null)))
                .toList();
        assertThat(found).as("company %s in the list of %s", code, staff.email()).hasSize(1);
        JsonNode count = found.getFirst().path("pendingMembershipRequests");
        assertThat(count.isIntegralNumber()).as("pendingMembershipRequests of %s", found.getFirst()).isTrue();
        return count.asInt();
    }

    // S2-AC-33

    @Test
    @DisplayName("S2-AC-33 the admin's list gives the pending requests oldest first in pages; an item has the "
            + "company, the manager who asked, the name and email the manager typed, the account of that email, "
            + "and the time; narrowed by company it holds that company's requests only")
    void pendingRequestsOldestFirst() {
        Scene s = scene();
        Session elsewhere = ManagerSteps.manager(fixtures, s.admin(), s.other().code());
        Session first = fixtures.customer(s.admin(), s.other().code());
        Session second = fixtures.customer(s.admin(), s.other().code());
        Existing agent = ExistingKind.AGENT.make(fixtures, s.admin(), s.other().code());
        Session ofCompany = fixtures.customer(s.admin(), s.code());
        Instant firstAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long firstId = request(s.admin(), s.manager(), s.code(), first.email(), "First typed");
        clock.advance(Duration.ofSeconds(1));
        long elsewhereId = request(s.admin(), elsewhere, s.other().code(), ofCompany.email(), "Elsewhere typed");
        clock.advance(Duration.ofSeconds(1));
        long secondId = request(s.admin(), s.manager(), s.code(), second.email(), "Second typed");
        clock.advance(Duration.ofSeconds(1));
        long agentId = request(s.admin(), s.manager(), s.code(), agent.email(), "Agent typed");

        List<JsonNode> ofTheCompany = requests(s.admin(), s.code());
        List<Long> all = pages(s.admin().client(), REQUESTS).stream().map(r -> r.path("id").asLong(-1)).toList();
        ApiResponse pageOne = s.admin().client().get(REQUESTS).query("companyCode", s.code()).query("size", 2)
                .expect(200);
        ApiResponse pageTwo = s.admin().client().get(REQUESTS).query("companyCode", s.code()).query("size", 2)
                .query("page", 2).expect(200);

        assertThat(ofTheCompany).extracting(r -> r.path("id").asLong(-1)).as("requests of %s, oldest first", s.code())
                .containsExactly(firstId, secondId, agentId);
        assertThat(ofTheCompany).extracting(r -> r.path("company").path("code").asString(null))
                .containsOnly(s.code());
        assertThat(all.stream().filter(List.of(firstId, elsewhereId, secondId, agentId)::contains).toList())
                .as("the four requests of this test in the list of every company, oldest first")
                .containsExactly(firstId, elsewhereId, secondId, agentId);
        assertThat(pageOne.jsonLong("page")).as("page").isEqualTo(1);
        assertThat(pageOne.jsonLong("size")).as("size").isEqualTo(2);
        assertThat(pageOne.jsonLong("totalElements")).as("totalElements").isEqualTo(3);
        assertThat(pageOne.jsonLong("totalPages")).as("totalPages").isEqualTo(2);
        assertThat(pageOne.json().path("items")).extracting(r -> r.path("id").asLong(-1))
                .containsExactly(firstId, secondId);
        assertThat(pageTwo.json().path("items")).extracting(r -> r.path("id").asLong(-1)).containsExactly(agentId);

        JsonNode item = ofTheCompany.getFirst();
        assertThat(item.propertyNames()).as("fields of %s", item).containsExactlyInAnyOrder("id", "company",
                "requestedBy", "name", "email", "account", "requestedAt");
        assertThat(item.path("company").path("name").asString(null)).isEqualTo(s.company().name());
        assertThat(item.path("requestedBy").path("accountId").asLong(-1)).isEqualTo(s.manager().accountId());
        assertThat(item.path("requestedBy").path("name").asString(null)).isEqualTo(s.manager().name());
        assertThat(item.path("requestedBy").path("email").asString(null)).isEqualTo(s.manager().email());
        assertThat(item.path("name").asString(null)).as("the name the manager typed").isEqualTo("First typed");
        assertThat(item.path("email").asString(null)).isEqualTo(first.email());
        assertThat(item.path("account").path("accountId").asLong(-1)).isEqualTo(first.accountId());
        assertThat(item.path("account").path("name").asString(null)).as("the account's own name")
                .isEqualTo(first.name());
        assertThat(item.path("account").path("role").asString(null)).isEqualTo("CUSTOMER");
        assertThat(item.path("account").path("status").asString(null)).isEqualTo("ACTIVE");
        assertThat(item.path("account").path("invitationCancelled").isBoolean()
                && !item.path("account").path("invitationCancelled").booleanValue())
                .as("invitationCancelled of %s", item).isTrue();
        assertThat(OffsetDateTime.parse(item.path("requestedAt").asString("")).toInstant()).as("requestedAt")
                .isEqualTo(firstAt);
        assertThat(ofTheCompany.getLast().path("account").path("role").asString(null)).as("role of an agent's account")
                .isEqualTo("AGENT");
    }

    @Test
    @DisplayName("S2-AC-33 narrowing the list by a company code that names no company is 400 INVALID_INPUT with "
            + "UNKNOWN_VALUE on companyCode; the code of an ARCHIVED company still narrows to its requests")
    void narrowingByCompanyCode() {
        Scene s = scene();
        Session elsewhere = ManagerSteps.manager(fixtures, s.admin(), s.other().code());
        long ofCompany = ask(s).requestId();
        long ofOther = request(s.admin(), elsewhere, s.other().code(),
                fixtures.customer(s.admin(), s.code()).email(), "Elsewhere typed");
        setCompanyStatus(s.admin(), s.code(), "ARCHIVED");

        ApiResponse unknown = s.admin().client().get(REQUESTS).query("companyCode", "NOSUCH9").send();
        List<JsonNode> ofArchived = requests(s.admin(), s.code());

        assertError(unknown, 400, "INVALID_INPUT");
        assertThat(fieldErrors(unknown)).as("fields of " + unknown.request())
                .containsExactly("companyCode:UNKNOWN_VALUE");
        assertThat(ofArchived).extracting(r -> r.path("id").asLong(-1)).as("requests of the archived %s", s.code())
                .containsExactly(ofCompany);
        assertThat(requests(s.admin(), s.other().code())).extracting(r -> r.path("id").asLong(-1))
                .containsExactly(ofOther);
    }

    @Test
    @DisplayName("S2-AC-33 the company list an agent gets says 0 pending membership requests where the admin's "
            + "says how many there are")
    void pendingCountIsForAdminsOnly() {
        Scene s = scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        ask(s);
        ask(s);

        assertThat(pendingCount(s.admin(), s.code())).as("pending requests the admin sees").isEqualTo(2);
        assertThat(pendingCount(agent, s.code())).as("pending requests the assigned agent sees").isZero();
        assertThat(pendingCount(s.admin(), s.other().code())).as("pending requests of the other company").isZero();
    }

    // S2-AC-34

    @Test
    @DisplayName("S2-AC-34 approving a request makes the account a customer of the company and closes the "
            + "request; the manager's row keeps its ID and now shows the account's own name and status")
    void approvingMakesTheAccountAMember() {
        Scene s = scene();
        Asked asked = ask(s);
        JsonNode rowBefore = row(s.manager(), s.code(), asked.target().email());
        assertThat(rowBefore.path("name").asString(null)).as("name of the request row").isEqualTo(asked.typedName());

        ApiResponse approved = approve(s.admin(), asked.requestId());

        approved.expectStatus(200);
        assertThat(approved.body()).as("body of the 200").isEmpty();
        assertThat(customerIds(s.admin(), s.code())).contains(asked.target().accountId());
        assertThat(customerIds(s.admin(), s.other().code())).contains(asked.target().accountId());
        assertThat(requests(s.admin(), s.code())).as("pending requests after approving").isEmpty();
        JsonNode rowAfter = row(s.manager(), s.code(), asked.target().email());
        assertThat(rowAfter.path("id").asString(null)).as("row ID after approving")
                .isEqualTo(rowBefore.path("id").asString(null)).matches(ManagerSteps.ROW_ID);
        assertThat(rowAfter.path("name").asString(null)).as("the account's own name").isEqualTo(asked.target().name());
        assertThat(rowAfter.path("status").asString(null)).isEqualTo("ACTIVE");
        assertThat(managerFlag(rowAfter)).as("manager mark of %s", rowAfter).isFalse();
        assertThat(myCompanies(asked.target()).keySet()).containsExactlyInAnyOrder(s.code(), s.other().code());
    }

    @Test
    @DisplayName("S2-AC-34 approving a request for an account whose invitation was cancelled makes it a member "
            + "and leaves the account and the invitation as they were: the row says SUSPENDED and the link is "
            + "still refused")
    void approvingDoesNotBringBackACancelledInvitation() {
        Scene s = scene();
        Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.other().code());
        s.admin().client().post("/api/console/invitations/" + invitation.accountId() + "/cancel").expect(204);
        long requestId = request(s.admin(), s.manager(), s.code(), invitation.email(), "Typed");
        JsonNode listed = requests(s.admin(), s.code()).getFirst();
        Map<String, Object> accountBefore = db.row("select * from account where id = ?", invitation.accountId());

        approve(s.admin(), requestId).expectStatus(200);

        assertThat(listed.path("account").path("invitationCancelled").asBoolean(false))
                .as("invitationCancelled of %s", listed).isTrue();
        assertThat(db.row("select * from account where id = ?", invitation.accountId())).isEqualTo(accountBefore);
        assertThat(customer(s.admin(), s.code(), invitation.accountId()).path("status").asString(null))
                .isEqualTo(listed.path("account").path("status").asString(null));
        assertThat(row(s.manager(), s.code(), invitation.email()).path("status").asString(null))
                .isEqualTo("SUSPENDED");
        assertThat(pendingInvitations(s.admin(), invitation.email())).singleElement()
                .satisfies(p -> assertThat(p.path("cancelled").asBoolean(false)).isTrue());
        assertError(acceptOnPortal(portal(), invitation.token()), 400, "INVITATION_INVALID");
    }

    // S2-AC-35

    @Test
    @DisplayName("S2-AC-35 rejecting a request closes it: the row leaves the manager's list, the account and its "
            + "companies stay as they were, and inviting the same email again makes a new request")
    void rejectingClosesTheRequest() {
        Scene s = scene();
        Asked asked = ask(s);
        Map<String, Object> accountBefore = db.row("select * from account where id = ?", asked.target().accountId());

        ApiResponse rejected = reject(s.admin(), asked.requestId());

        rejected.expectStatus(200);
        assertThat(rejected.body()).as("body of the 200").isEmpty();
        assertThat(requests(s.admin(), s.code())).as("pending requests after rejecting").isEmpty();
        assertThat(emails(s.manager(), s.code())).doesNotContain(asked.target().email());
        assertThat(db.row("select * from account where id = ?", asked.target().accountId())).isEqualTo(accountBefore);
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(asked.target().accountId());
        assertThat(myCompanies(asked.target()).keySet()).containsExactly(s.other().code());

        invited(s.manager(), s.code(), asked.target().email(), "Typed again");

        assertThat(pendingId(s.admin(), s.code(), asked.target().email())).as("ID of the new request")
                .isNotEqualTo(asked.requestId());
        assertThat(row(s.manager(), s.code(), asked.target().email()).path("status").asString(null))
                .isEqualTo("INVITED");
    }

    // S2-AC-36

    @ParameterizedTest(name = "{0}")
    @CsvSource({"the account of an agent, NOT_A_CUSTOMER", "the account of an admin, NOT_A_CUSTOMER",
            "a company archived after the request, COMPANY_NOT_ACTIVE"})
    @DisplayName("S2-AC-36 approving a request for an account that is no customer, or of an archived company, is "
            + "400 and changes nothing; rejecting it works")
    void requestThatCannotBeApproved(String what, String code) {
        Scene s = scene();
        Existing existing = switch (what) {
            case "the account of an agent" -> ExistingKind.AGENT.make(fixtures, s.admin(), s.other().code());
            case "the account of an admin" -> ExistingKind.ADMIN.make(fixtures, s.admin(), s.other().code());
            default -> ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        };
        long requestId = request(s.admin(), s.manager(), s.code(), existing.email(), "Typed");
        if (code.equals("COMPANY_NOT_ACTIVE")) {
            setCompanyStatus(s.admin(), s.code(), "ARCHIVED");
        }

        ApiResponse approved = approve(s.admin(), requestId);

        assertError(approved, 400, code);
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(existing.accountId());
        assertThat(pendingEmails(s.admin(), s.code())).as("the request is still pending").containsExactly(
                existing.email());

        reject(s.admin(), requestId).expectStatus(200);

        assertThat(requests(s.admin(), s.code())).as("pending requests after rejecting").isEmpty();
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(existing.accountId());
    }

    // S2-AC-37

    @ParameterizedTest(name = "{0}, then {1}")
    @CsvSource({"approve, approve, 200,", "reject, reject, 200,", "approve, reject, 409, REQUEST_CLOSED",
            "reject, approve, 409, REQUEST_CLOSED", "withdraw, approve, 409, REQUEST_CLOSED",
            "withdraw, reject, 409, REQUEST_CLOSED"})
    @DisplayName("S2-AC-37 the same decision on a closed request is 200 again; the other decision, or any decision "
            + "on a request the manager withdrew, is 409 REQUEST_CLOSED and changes nothing")
    void closedRequest(String first, String then, int status, String code) {
        Scene s = scene();
        Asked asked = ask(s);
        switch (first) {
            case "approve" -> approve(s.admin(), asked.requestId()).expectStatus(200);
            case "reject" -> reject(s.admin(), asked.requestId()).expectStatus(200);
            default -> withdraw(s.manager(), s.code(), asked.target().email());
        }
        List<Long> customersBefore = customerIds(s.admin(), s.code());
        List<String> rowsBefore = emails(s.manager(), s.code());

        ApiResponse response = then.equals("approve") ? approve(s.admin(), asked.requestId())
                : reject(s.admin(), asked.requestId());

        if (code == null) {
            response.expectStatus(status);
            assertThat(response.body()).as("body of the repeated %s", then).isEmpty();
        } else {
            assertError(response, status, code);
        }
        assertThat(customerIds(s.admin(), s.code())).as("customers of the company").isEqualTo(customersBefore);
        assertThat(customersBefore.contains(asked.target().accountId())).as("the account is a customer")
                .isEqualTo(first.equals("approve"));
        assertThat(emails(s.manager(), s.code())).as("rows of the member list").isEqualTo(rowsBefore);
        assertThat(requests(s.admin(), s.code())).as("pending requests").isEmpty();
    }

    // S2-AC-38

    @ParameterizedTest(name = "then {0}")
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("S2-AC-38 adding the account to the company on the console closes the pending request as "
            + "approved: it leaves the list, the manager's row keeps its ID, approving is 200 and rejecting 409")
    void addingTheCustomerClosesTheRequest(String then) {
        Scene s = scene();
        Asked asked = ask(s);
        String rowBefore = rowId(s.manager(), s.code(), asked.target().email());

        addCustomer(s.admin(), s.code(), asked.target().accountId());

        assertThat(requests(s.admin(), s.code())).as("pending requests after adding the customer").isEmpty();
        assertThat(rowId(s.manager(), s.code(), asked.target().email())).as("row ID after adding the customer")
                .isEqualTo(rowBefore);
        assertThat(row(s.manager(), s.code(), asked.target().email()).path("name").asString(null))
                .isEqualTo(asked.target().name());
        if (then.equals("approve")) {
            approve(s.admin(), asked.requestId()).expectStatus(200);
        } else {
            assertError(reject(s.admin(), asked.requestId()), 409, "REQUEST_CLOSED");
        }
        assertThat(customerIds(s.admin(), s.code())).containsOnlyOnce(asked.target().accountId());
    }
}
