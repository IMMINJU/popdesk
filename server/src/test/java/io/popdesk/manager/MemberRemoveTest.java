package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.acceptOnPortal;
import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.assertSameResponse;
import static io.popdesk.manager.ManagerSteps.customer;
import static io.popdesk.manager.ManagerSteps.customerAccounts;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.managerFlag;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.myCompanies;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.resendTo;
import static io.popdesk.manager.ManagerSteps.row;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Existing;
import io.popdesk.manager.ManagerSteps.ExistingKind;
import io.popdesk.manager.ManagerSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * A manager takes rows out of the member list, and what sending an invitation again refuses, without
 * SMTP. Sending again with mail is in {@link MemberMailTest}.
 */
class MemberRemoveTest extends BareAppTest {

    private Scene scene() {
        return ManagerSteps.scene(fixtures);
    }

    private static ApiResponse remove(Scene s, String email) {
        return ManagerSteps.remove(s.manager(), s.code(), email);
    }

    // S2-AC-24

    @Test
    @DisplayName("S2-AC-03, S2-AC-24 a member a manager takes out loses only that company, also one who is a "
            + "manager of another company: the account stays ACTIVE and a manager in its other company, still "
            + "reads the issue it filed there, and can no longer file there")
    void removedMemberKeepsTheRest() {
        Scene s = scene();
        Session leaver = fixtures.customer(s.admin(), s.code(), s.other().code());
        mark(s.admin(), s.other().code(), leaver.accountId());
        Issue own = fixtures.fileIssue(leaver, s.code());

        ApiResponse removed = remove(s, leaver.email());

        removed.expectStatus(204);
        assertThat(removed.body()).as("body of the 204").isEmpty();
        assertThat(emails(s.manager(), s.code())).doesNotContain(leaver.email());
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(leaver.accountId());
        JsonNode inTheOther = customer(s.admin(), s.other().code(), leaver.accountId());
        assertThat(inTheOther.path("status").asString(null)).isEqualTo("ACTIVE");
        assertThat(managerFlag(inTheOther)).as("still a manager of the other company").isTrue();
        assertThat(myCompanies(leaver).keySet()).containsExactly(s.other().code());
        assertThat(leaver.client().get("/api/portal/issues/" + own.key()).expect(200).jsonText("key"))
                .isEqualTo(own.key());
        ApiResponse filing = leaver.client().post("/api/portal/issues")
                .json(Map.of("companyCode", s.code(), "title", Unique.text("After leaving"), "content", "Steps."))
                .send();
        assertError(filing, 404, "NOT_FOUND");
        fixtures.fileIssue(leaver, s.other().code());
    }

    // S2-AC-25

    @Test
    @DisplayName("S2-AC-25 taking out a member who has not accepted and belongs to this company only also "
            + "cancels the invitation: the admin sees it cancelled and the link is refused")
    void removingAPendingMemberOfThisCompanyOnlyCancelsTheInvitation() {
        Scene s = scene();
        Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());

        remove(s, invitation.email()).expectStatus(204);

        assertThat(emails(s.manager(), s.code())).doesNotContain(invitation.email());
        assertThat(pendingInvitations(s.admin(), invitation.email())).singleElement().satisfies(p -> {
            assertThat(p.path("accountId").asLong(-1)).isEqualTo(invitation.accountId());
            assertThat(p.path("cancelled").asBoolean(false)).isTrue();
        });
        assertError(acceptOnPortal(portal(), invitation.token()), 400, "INVITATION_INVALID");
    }

    @Test
    @DisplayName("S2-AC-25 taking out a member who has not accepted but belongs to another company too leaves "
            + "the invitation: the link is accepted and the person is in the other company only")
    void removingAPendingMemberOfTwoCompaniesKeepsTheInvitation() {
        Scene s = scene();
        Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.code(), s.other().code());

        remove(s, invitation.email()).expectStatus(204);

        assertThat(pendingInvitations(s.admin(), invitation.email())).singleElement().satisfies(p -> {
            assertThat(p.path("cancelled").asBoolean(true)).isFalse();
            assertThat(p.path("companies")).extracting(c -> c.path("code").asString(null))
                    .containsExactly(s.other().code());
        });
        Session accepted = fixtures.accept(invitation);
        assertThat(myCompanies(accepted).keySet()).containsExactly(s.other().code());
    }

    // S2-AC-26

    @ParameterizedTest(name = "a request for {0}")
    @EnumSource(value = ExistingKind.class, names = {"CUSTOMER_OF_ANOTHER_COMPANY", "INVITED_CUSTOMER"})
    @DisplayName("S2-AC-26 taking out the row of a pending membership request withdraws the request: it leaves "
            + "the admin's list, and the account stays as it was, also when the account has not accepted yet "
            + "(its invitation is not cancelled and its link still works)")
    void removingARequestRowWithdrawsIt(ExistingKind kind) {
        Scene s = scene();
        Existing existing = kind.make(fixtures, s.admin(), s.other().code());
        invited(s.manager(), s.code(), existing.email(), "Requested");
        assertThat(requests(s.admin(), s.code())).hasSize(1);
        Map<String, Object> accountBefore = db.row("select * from account where id = ?", existing.accountId());

        remove(s, existing.email()).expectStatus(204);

        assertThat(requests(s.admin(), s.code())).isEmpty();
        assertThat(emails(s.manager(), s.code())).doesNotContain(existing.email());
        assertThat(db.row("select * from account where id = ?", existing.accountId())).isEqualTo(accountBefore);
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(existing.accountId());
        assertThat(customerIds(s.admin(), s.other().code())).contains(existing.accountId());
        if (existing.token() != null) {
            assertThat(pendingInvitations(s.admin(), existing.email())).singleElement()
                    .satisfies(p -> assertThat(p.path("cancelled").asBoolean(true)).isFalse());
            acceptOnPortal(portal(), existing.token()).expectStatus(204);
        }
    }

    // S2-AC-27

    @Test
    @DisplayName("S2-AC-24, S2-AC-27 taking out a member, a member who has not accepted (of this company only, "
            + "and of another company too), and a pending membership request answer the same: status, body, "
            + "and headers; the member's account stays ACTIVE")
    void sameAnswerForEveryKindOfRow() {
        Scene s = scene();
        Session member = fixtures.customer(s.admin(), s.code());
        Invitation pending = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        Invitation pendingInTwo = fixtures.invite(s.admin(), Role.CUSTOMER, s.code(), s.other().code());
        Existing existing = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        invited(s.manager(), s.code(), existing.email(), "Requested");

        ApiResponse ofMember = remove(s, member.email());
        ApiResponse ofPending = remove(s, pending.email());
        ApiResponse ofPendingInTwo = remove(s, pendingInTwo.email());
        ApiResponse ofRequest = remove(s, existing.email());

        ofMember.expectStatus(204);
        assertThat(ofMember.body()).as("body of the 204").isEmpty();
        assertSameResponse(ofMember, ofPending);
        assertSameResponse(ofMember, ofPendingInTwo);
        assertSameResponse(ofMember, ofRequest);
        assertThat(emails(s.manager(), s.code())).containsExactly(s.manager().email());
        assertThat(customerAccounts(s.admin(), "ACTIVE")).as("ACTIVE customer accounts").contains(member.accountId());
        fixtures.accept(pendingInTwo);
    }

    // S2-AC-28

    @Test
    @DisplayName("S2-AC-28 a manager taking out their own row gets 400 OWN_ACCOUNT, and another manager's row "
            + "400 MEMBER_IS_MANAGER; both stay managers of the company")
    void notOneselfNorAnotherManager() {
        Scene s = scene();
        Session colleague = ManagerSteps.manager(fixtures, s.admin(), s.code());

        ApiResponse own = remove(s, s.manager().email());
        ApiResponse ofColleague = remove(s, colleague.email());

        assertError(own, 400, "OWN_ACCOUNT");
        assertError(ofColleague, 400, "MEMBER_IS_MANAGER");
        for (Session person : new Session[] {s.manager(), colleague}) {
            assertThat(managerFlag(row(s.manager(), s.code(), person.email())))
                    .as("manager mark of " + person.email()).isTrue();
            assertThat(managerFlag(customer(s.admin(), s.code(), person.accountId()))).isTrue();
        }
    }

    // S2-AC-31

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"accepted", "suspended", "not accepted and in another company too",
            "not accepted, the invitation cancelled by an admin"})
    @DisplayName("S2-AC-31 sending again for a member who accepted, is suspended (also one whose invitation an "
            + "admin cancelled, SUSPENDED in the list), or belongs to another company too is the same 400 "
            + "INVITATION_NOT_RESENDABLE whatever the reason, and changes nothing")
    void notResendable(String state) {
        Scene s = scene();
        Session accepted = fixtures.customer(s.admin(), s.code());
        Invitation inTwo = fixtures.invite(s.admin(), Role.CUSTOMER, s.code(), s.other().code());
        Invitation cancelled = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        s.admin().client().post("/api/console/invitations/" + cancelled.accountId() + "/cancel").expect(204);
        String email = switch (state) {
            case "accepted" -> fixtures.customer(s.admin(), s.code()).email();
            case "suspended" -> {
                Session suspended = fixtures.customer(s.admin(), s.code());
                ManagerSteps.suspend(s.admin(), suspended.accountId());
                yield suspended.email();
            }
            case "not accepted and in another company too" -> inTwo.email();
            default -> cancelled.email();
        };
        JsonNode rowBefore = row(s.manager(), s.code(), email);
        assertThat(row(s.manager(), s.code(), cancelled.email()).path("status").asString(null))
                .as("status of the row of a cancelled invitation").isEqualTo("SUSPENDED");

        ApiResponse reference = resendTo(s.manager(), s.code(), accepted.email());
        ApiResponse response = resendTo(s.manager(), s.code(), email);

        assertError(reference, 400, "INVITATION_NOT_RESENDABLE");
        assertError(response, 400, "INVITATION_NOT_RESENDABLE");
        assertSameResponse(reference, response);
        assertThat(row(s.manager(), s.code(), email)).isEqualTo(rowBefore);
        assertThat(pendingInvitations(s.admin(), cancelled.email())).singleElement()
                .satisfies(p -> assertThat(p.path("cancelled").asBoolean(false)).isTrue());
        assertError(acceptOnPortal(portal(), cancelled.token()), 400, "INVITATION_INVALID");
        fixtures.accept(inTwo);
    }
}
