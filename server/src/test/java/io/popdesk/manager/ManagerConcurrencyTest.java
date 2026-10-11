package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.customerIds;
import static io.popdesk.manager.ManagerSteps.emails;
import static io.popdesk.manager.ManagerSteps.invite;
import static io.popdesk.manager.ManagerSteps.managerFlag;
import static io.popdesk.manager.ManagerSteps.managerOf;
import static io.popdesk.manager.ManagerSteps.member;
import static io.popdesk.manager.ManagerSteps.requests;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rowId;
import static io.popdesk.manager.RequestSteps.approve;
import static io.popdesk.manager.RequestSteps.reject;
import static io.popdesk.manager.RequestSteps.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two requests of this slice that arrive at the same time: one of them wins, the other gets its
 * ordinary refusal, and none is a 500. Which one wins is open, so each test repeats the pair
 * {@link #ROUNDS} times with new accounts and checks every round by what the answers say happened.
 */
class ManagerConcurrencyTest extends BareAppTest {

    private static final int ROUNDS = 10;

    private static void assertNoServerError(List<ApiResponse> responses) {
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.request() + ": " + r.snippet())
                .isLessThan(500));
    }

    // S2-AC-37

    @Test
    @DisplayName("S2-AC-37 approving and rejecting one request at the same time: one is 200, the other 409 "
            + "REQUEST_CLOSED, none is a 500, and the account is a customer only when the approval won")
    void approvalAndRejectionAtOnce() {
        Scene s = ManagerSteps.scene(fixtures);
        Session second = fixtures.admin(s.admin());

        for (int round = 1; round <= ROUNDS; round++) {
            Session target = fixtures.customer(s.admin(), s.other().code());
            long id = request(s.admin(), s.manager(), s.code(), target.email(), "Typed");

            List<ApiResponse> responses = Concurrently.send(2, List.of(() -> approve(s.admin(), id),
                    () -> reject(second, id)));

            ApiResponse approved = responses.get(0);
            ApiResponse rejected = responses.get(1);
            assertNoServerError(responses);
            assertThat(responses).extracting(ApiResponse::status).as("statuses in round %s", round)
                    .containsExactlyInAnyOrder(200, 409);
            assertError(approved.status() == 409 ? approved : rejected, 409, "REQUEST_CLOSED");
            assertThat(customerIds(s.admin(), s.code()).contains(target.accountId()))
                    .as("the account is a customer of the company in round %s", round)
                    .isEqualTo(approved.status() == 200);
            assertThat(requests(s.admin(), s.code())).as("pending requests after round %s", round).isEmpty();
            assertThat(emails(s.manager(), s.code()).contains(target.email()))
                    .as("the account is a row of the member list in round %s", round)
                    .isEqualTo(approved.status() == 200);
        }
    }

    // S2-AC-14, S2-AC-17

    @Test
    @DisplayName("S2-AC-14, S2-AC-17 two managers inviting the same existing email to their company at the same "
            + "time leave one pending request and one row: both are 204, or one is 204 and the other 409 "
            + "ALREADY_LISTED; none is a 500")
    void twoInvitationsOfOneExistingEmailAtOnce() {
        Scene s = ManagerSteps.scene(fixtures);
        Session colleague = ManagerSteps.manager(fixtures, s.admin(), s.code());

        for (int round = 1; round <= ROUNDS; round++) {
            Session target = fixtures.customer(s.admin(), s.other().code());

            List<ApiResponse> responses = Concurrently.send(2, List.of(
                    () -> invite(s.manager(), s.code(), target.email(), "Typed by one"),
                    () -> invite(colleague, s.code(), target.email(), "Typed by the other")));

            assertNoServerError(responses);
            List<ApiResponse> refused = responses.stream().filter(r -> r.status() != 204).toList();
            assertThat(refused).as("invitations that were not 204 in round %s", round).hasSizeLessThan(2)
                    .allSatisfy(r -> assertError(r, 409, "ALREADY_LISTED"));
            assertThat(requests(s.admin(), s.code()).stream()
                    .filter(r -> target.email().equals(r.path("email").asString(null))).toList())
                    .as("pending requests for %s after round %s", target.email(), round).hasSize(1);
            assertThat(row(s.manager(), s.code(), target.email()).path("status").asString(null))
                    .isEqualTo("INVITED");
            assertThat(customerIds(s.admin(), s.code())).doesNotContain(target.accountId());
        }
    }

    // S2-AC-11

    @Test
    @DisplayName("S2-AC-11 an admin unmarking a manager while that manager takes a member out: the removal is "
            + "204 and the member is out, or 403 FORBIDDEN and the member stays; none is a 500")
    void unmarkingWhileTheManagerRemoves() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();

        for (int round = 1; round <= ROUNDS; round++) {
            Session manager = ManagerSteps.manager(fixtures, admin, code);
            Session victim = fixtures.customer(admin, code);
            String victimRow = rowId(manager, code, victim.email());

            List<ApiResponse> responses = Concurrently.send(2, List.of(
                    () -> admin.client().delete(managerOf(code, manager.accountId())).send(),
                    () -> manager.client().delete(member(code, victimRow)).send()));

            ApiResponse unmarked = responses.get(0);
            ApiResponse removed = responses.get(1);
            assertNoServerError(responses);
            unmarked.expectStatus(200);
            assertThat(managerFlag(ManagerSteps.customer(admin, code, manager.accountId())))
                    .as("manager mark after round %s", round).isFalse();
            if (removed.status() != 204) {
                assertError(removed, 403, "FORBIDDEN");
            }
            assertThat(customerIds(admin, code).contains(victim.accountId()))
                    .as("the member is still a customer of the company in round %s (the removal was %s)", round,
                            removed.status())
                    .isEqualTo(removed.status() == 403);
        }
    }
}
