package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.privacy.PrivacySteps.ACCOUNTS;
import static io.popdesk.privacy.PrivacySteps.anonymize;
import static io.popdesk.privacy.PrivacySteps.anonymizedAccount;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Nothing brings an anonymized account back (S10A-AC-13, acceptance.md 5절 4): eight admin actions on it are 400
 * {@code ACCOUNT_ANONYMIZED}, also where the request would otherwise get another 400; suspending is 200 as now.
 *
 * <p>The account is a customer who accepted their invitation, of one company, with a membership request of another
 * company pending for them. So renaming with an empty name ({@code INVALID_INPUT}), the agent role
 * ({@code CUSTOMER_ROLE_FIXED}), and resending the invitation ({@code INVITATION_ALREADY_ACCEPTED}) would be another
 * 400, and the invitation link another 4xx, if the check did not come first.
 */
class AnonymizedAccountBlockedTest extends PrivacyAppTest {

    /** The eight actions. */
    enum Action {
        ANONYMIZE,
        RENAME,
        CHANGE_ROLE,
        REACTIVATE,
        RESET_PASSWORD,
        RESEND_INVITATION,
        INVITATION_LINK,
        ADD_TO_COMPANY,
        APPROVE_MEMBERSHIP_REQUEST
    }

    /** An anonymized customer, a company they are not in, and the ID of a request of that company for them. */
    private record Blocked(Session admin, long accountId, String otherCode, long requestId) {}

    private Blocked anonymizedCustomer() {
        ManagerSteps.Scene scene = ManagerSteps.scene(fixtures, fixtures.signInAdmin());
        Session customer = fixtures.customer(scene.admin(), scene.other().code());
        ManagerSteps.invited(scene.manager(), scene.code(), customer.email(), Unique.text("Requested"));
        List<JsonNode> requests = ManagerSteps.requests(scene.admin(), scene.code()).stream()
                .filter(r -> r.path("account").path("accountId").asLong(-1) == customer.accountId()).toList();
        assertThat(requests).as("pending requests of %s for the customer", scene.code()).hasSize(1);
        anonymize(scene.admin(), customer.accountId());
        return new Blocked(scene.admin(), customer.accountId(), scene.code(), requests.getFirst().path("id").asLong());
    }

    private static ApiResponse send(Action action, Blocked b) {
        var client = b.admin().client();
        String account = ACCOUNTS + "/" + b.accountId();
        return switch (action) {
            case ANONYMIZE -> client.post(account + "/anonymize").send();
            case RENAME -> client.patch(account).json(Map.of("name", "")).send();
            case CHANGE_ROLE -> client.put(account + "/role").json(Map.of("role", "AGENT")).send();
            case REACTIVATE -> client.post(account + "/reactivate").send();
            case RESET_PASSWORD -> client.post(account + "/password-reset").send();
            case RESEND_INVITATION -> client.post("/api/console/invitations/" + b.accountId() + "/resend").send();
            case INVITATION_LINK -> client.post("/api/console/invitations/" + b.accountId() + "/link").send();
            case ADD_TO_COMPANY -> client.put("/api/console/companies/" + b.otherCode() + "/customers/"
                    + b.accountId()).send();
            case APPROVE_MEMBERSHIP_REQUEST -> client.post("/api/console/membership-requests/" + b.requestId()
                    + "/approve").send();
        };
    }

    private static void assertStillAnonymous(Blocked b) {
        JsonNode account = anonymizedAccount(b.admin(), b.accountId());
        assertThat(account.path("name").asString(null)).as("name of %s", account).isEqualTo(ANONYMOUS_NAME);
        assertThat(account.path("email").asString(null)).as("email of %s", account)
                .isEqualTo(anonymousEmail(b.accountId()));
        assertThat(account.path("status").asString(null)).as("status of %s", account).isEqualTo("SUSPENDED");
        assertThat(account.path("role").asString(null)).as("role of %s", account).isEqualTo("CUSTOMER");
        assertThat(account.path("anonymized").asBoolean(false)).as("anonymized in %s", account).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Action.class)
    @DisplayName("S10A-AC-13 on an anonymized account anonymizing, renaming, the role, reactivating, the password "
            + "reset, resending and the invitation link, adding to a company, and approving its membership request "
            + "are 400 ACCOUNT_ANONYMIZED before any other 400, and the account stays anonymous")
    void refused(Action action) {
        Blocked b = anonymizedCustomer();

        assertRefused(send(action, b), 400, "ACCOUNT_ANONYMIZED");

        assertStillAnonymous(b);
        if (action == Action.ADD_TO_COMPANY) {
            assertThat(ManagerSteps.customerIds(b.admin(), b.otherCode())).as("customers of %s", b.otherCode())
                    .doesNotContain(b.accountId());
        }
    }

    @Test
    @DisplayName("S10A-AC-13 suspending an anonymized account is 200, as for any suspended account, and changes "
            + "nothing")
    void suspendIsAllowed() {
        Blocked b = anonymizedCustomer();

        ApiResponse suspended = b.admin().client().post(ACCOUNTS + "/" + b.accountId() + "/suspend").send()
                .expectStatus(200);

        assertThat(suspended.json().path("anonymized").asBoolean(false)).as("anonymized in %s", suspended.snippet())
                .isTrue();
        assertStillAnonymous(b);
    }
}
