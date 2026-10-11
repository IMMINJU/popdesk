package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.acceptOnPortal;
import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.assertSameResponse;
import static io.popdesk.manager.ManagerSteps.customer;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.member;
import static io.popdesk.manager.ManagerSteps.myCompanies;
import static io.popdesk.manager.ManagerSteps.pendingInvitations;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.resend;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rowId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Existing;
import io.popdesk.manager.ManagerSteps.ExistingKind;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A manager takes rows out of the member list, and what sending an invitation again refuses, without
 * SMTP. Sending again with mail is in {@link MemberMailTest}.
 */
class MemberRemoveTest extends BareAppTest {

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

    private static ApiResponse remove(Scene s, String email) {
        return s.manager().client().delete(member(s.code(), rowId(s.manager(), s.code(), email))).send();
    }

    // S2-AC-24

    @Test
    @DisplayName("S2-AC-24 a member a manager takes out loses only that company: the account stays ACTIVE in "
            + "its other company, still reads the issue it filed there, and can no longer file there")
    void removedMemberKeepsTheRest() {
        Scene s = scene();
        Session leaver = fixtures.customer(s.admin(), s.code(), s.other().code());
        Issue own = fixtures.fileIssue(leaver, s.code());

        ApiResponse removed = remove(s, leaver.email());

        removed.expectStatus(204);
        assertThat(removed.body()).as("body of the 204").isEmpty();
        assertThat(emails(s.manager(), s.code())).doesNotContain(leaver.email());
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(leaver.accountId());
        assertThat(customer(s.admin(), s.other().code(), leaver.accountId()).path("status").asString(null))
                .isEqualTo("ACTIVE");
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

    @Test
    @DisplayName("S2-AC-26 taking out the row of a pending membership request withdraws the request: it leaves "
            + "the admin's list, and the account stays as it was")
    void removingARequestRowWithdrawsIt() {
        Scene s = scene();
        Existing existing = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        invited(s.manager(), s.code(), existing.email(), "Requested");
        assertThat(requests(s.admin(), s.code())).hasSize(1);
        Map<String, Object> accountBefore = db.row("select * from account where id = ?", existing.accountId());

        remove(s, existing.email()).expectStatus(204);

        assertThat(requests(s.admin(), s.code())).isEmpty();
        assertThat(emails(s.manager(), s.code())).doesNotContain(existing.email());
        assertThat(db.row("select * from account where id = ?", existing.accountId())).isEqualTo(accountBefore);
        assertThat(customerIds(s.admin(), s.code())).doesNotContain(existing.accountId());
        assertThat(customerIds(s.admin(), s.other().code())).contains(existing.accountId());
    }

    // S2-AC-27

    @Test
    @DisplayName("S2-AC-27 taking out a member, a member who has not accepted, and a pending membership request "
            + "answer the same: status, body, and headers")
    void sameAnswerForEveryKindOfRow() {
        Scene s = scene();
        Session member = fixtures.customer(s.admin(), s.code());
        Invitation pending = fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        Existing existing = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, s.admin(), s.other().code());
        invited(s.manager(), s.code(), existing.email(), "Requested");

        ApiResponse ofMember = remove(s, member.email());
        ApiResponse ofPending = remove(s, pending.email());
        ApiResponse ofRequest = remove(s, existing.email());

        ofMember.expectStatus(204);
        assertThat(ofMember.body()).as("body of the 204").isEmpty();
        assertSameResponse(ofMember, ofPending);
        assertSameResponse(ofMember, ofRequest);
        assertThat(emails(s.manager(), s.code())).containsExactly(s.manager().email());
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
            assertThat(row(s.manager(), s.code(), person.email()).path("manager").asBoolean(false))
                    .as("manager mark of " + person.email()).isTrue();
            assertThat(customer(s.admin(), s.code(), person.accountId()).path("manager").asBoolean(false)).isTrue();
        }
    }

    // S2-AC-31

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"accepted", "suspended", "not accepted and in another company too"})
    @DisplayName("S2-AC-31 sending again for a member who accepted, is suspended, or belongs to another company "
            + "too is the same 400 INVITATION_NOT_RESENDABLE whatever the reason, and changes nothing")
    void notResendable(String state) {
        Scene s = scene();
        Session accepted = fixtures.customer(s.admin(), s.code());
        Invitation inTwo = fixtures.invite(s.admin(), Role.CUSTOMER, s.code(), s.other().code());
        String email = switch (state) {
            case "accepted" -> fixtures.customer(s.admin(), s.code()).email();
            case "suspended" -> {
                Session suspended = fixtures.customer(s.admin(), s.code());
                ManagerSteps.suspend(s.admin(), suspended.accountId());
                yield suspended.email();
            }
            default -> inTwo.email();
        };
        String statusBefore = row(s.manager(), s.code(), email).path("status").asString(null);

        ApiResponse reference = s.manager().client()
                .post(resend(s.code(), rowId(s.manager(), s.code(), accepted.email()))).send();
        ApiResponse response = s.manager().client().post(resend(s.code(), rowId(s.manager(), s.code(), email)))
                .send();

        assertError(reference, 400, "INVITATION_NOT_RESENDABLE");
        assertError(response, 400, "INVITATION_NOT_RESENDABLE");
        assertSameResponse(reference, response);
        assertThat(row(s.manager(), s.code(), email).path("status").asString(null)).isEqualTo(statusBefore);
        fixtures.accept(inTwo);
    }
}
