package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.notify.DeliverySteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order of checks on the two endpoints of slice 4b (conventions "Order of checks"): 401, the
 * role (403), a missing row (404), then a channel that is off (400). The app has no SMTP here, so
 * mail is a channel that is off. The limit of the test mail is counted right after the sign-in
 * check, before the channel is looked at.
 *
 * <p>An admin of its own in each test: the limit is per account and the clock stands still.
 */
class DeliveryCheckOrderTest extends BareAppTest {

    @Test
    @DisplayName("S4B-AC-37 sending a failed row again and the test mail check in order: 401 without a session, "
            + "403 for an agent whatever the ID, 404 for an admin's missing row, 400 CHANNEL_DISABLED last")
    void checksComeInOrder() {
        Session first = fixtures.signInAdmin();
        ApiClient admin = fixtures.admin(first).client();
        ApiClient agent = fixtures.agent(first).client();
        ApiClient anonymous = console();

        assertError(anonymous.post(Delivery.resendPath("not-a-number")).send(), 401, "UNAUTHENTICATED");
        assertError(anonymous.post(Delivery.TEST_MAIL).send(), 401, "UNAUTHENTICATED");
        assertError(agent.post(Delivery.resendPath(DeliverySteps.NO_ROW)).send(), 403, "FORBIDDEN");
        assertError(agent.post(Delivery.resendPath("not-a-number")).send(), 403, "FORBIDDEN");
        assertError(agent.post(Delivery.TEST_MAIL).send(), 403, "FORBIDDEN");
        assertError(admin.post(Delivery.resendPath(DeliverySteps.NO_ROW)).send(), 404, "NOT_FOUND");
        assertError(admin.post(Delivery.resendPath("not-a-number")).send(), 404, "NOT_FOUND");
        assertError(admin.post(Delivery.TEST_MAIL).send(), 400, "CHANNEL_DISABLED");
    }

    @Test
    @DisplayName("S4B-AC-25 S4B-AC-26 S4B-AC-27 S4B-AC-37 without SMTP settings a test mail is 400 "
            + "CHANNEL_DISABLED, and its limit is counted before the channel is checked: five requests get the 400 "
            + "and the sixth within 60 seconds gets 429 with Retry-After; none leaves an audit entry")
    void limitIsCountedBeforeTheChannelCheck() {
        Session tester = fixtures.admin(fixtures.signInAdmin());
        for (int i = 0; i < 5; i++) {
            assertError(Delivery.testMailRequest(tester).send(), 400, "CHANNEL_DISABLED");
        }

        ApiResponse sixth = Delivery.testMailRequest(tester).send();

        assertError(sixth, 429, "RATE_LIMITED");
        assertThat(sixth.header("Retry-After")).as("Retry-After").isNotNull().matches("[1-9][0-9]?");
        assertThat(DeliverySteps.auditEntries(tester, DeliverySteps.TEST_MAIL_SENT, tester.accountId()))
                .as("TEST_MAIL_SENT entries of the admin").isEmpty();
    }
}
