package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.notify.DeliverySteps;
import io.popdesk.notify.DeliverySteps.FailedWebhook;
import io.popdesk.support.ApiClient;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may send a failed row again and send a test mail (COM-2, D-43): admins only, on the console
 * host only. A refused request changes nothing: the row stays failed and no mail goes out.
 *
 * <p>A customer has no session the console counts ({@code audit.AuditLogAccessTest}): on the portal
 * host both paths are 404, and the customer's session cookies carried to the console host are no
 * session there, so the answer is 401.
 */
class DeliveryAccessTest extends Delivery.PlainApp {

    @BeforeEach
    void cleanStart() {
        startClean();
    }

    @AfterEach
    void leaveNoFailedRow() {
        smtp().pass();
        webhook().respondWith(204);
        startClean();
    }

    @Test
    @DisplayName("S4B-AC-19 an agent gets 403 for sending a failed row again and for a test mail, a customer whose "
            + "session cookies are carried to the console host gets 401, and nothing happens: the row stays "
            + "failed and unsent and no mail goes out; the admin then does both")
    void agentIsForbiddenAndNothingHappens() {
        Cast c = cast();
        FailedWebhook failed = DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        Session admin = admin();
        Session agent = fixtures.signInAgain(c.agent());
        int attempts = Delivery.webhooks(webhook(), failed.issueKey()).size();

        assertError(Delivery.resendRequest(agent, failed.id()).send(), 403, "FORBIDDEN");
        assertError(Delivery.testMailRequest(agent).send(), 403, "FORBIDDEN");
        Session customer = fixtures.signInAgain(c.customer());
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);
        assertError(carried.post(Delivery.resendPath(failed.id())).send(), 401, "UNAUTHENTICATED");
        assertError(carried.post(Delivery.TEST_MAIL).send(), 401, "UNAUTHENTICATED");

        awaitALaterMail(admin);
        assertThat(DeliverySteps.mailsTo(mail(), agent.email())).as("mails to the agent").isEmpty();
        assertThat(DeliverySteps.mailsTo(mail(), customer.email())).as("mails to the customer").isEmpty();
        assertThat(Delivery.isFailed(admin, failed.id())).as("the row is still in the failed list").isTrue();
        Wait.holds("no new attempt of the row", Duration.ofSeconds(2),
                () -> Delivery.webhooks(webhook(), failed.issueKey()).size() == attempts);
        assertThat(Delivery.isFailed(admin, failed.id())).as("the row is still in the failed list").isTrue();
        // What the agent may not do, the admin does.
        Delivery.resend(admin, failed.id());
        Wait.until("the webhook sent again for the admin",
                () -> Delivery.webhooks(webhook(), failed.issueKey()).size() > attempts ? failed : null);
        assertThat(Delivery.testMail(admin).path("ok").asBoolean(false)).as("ok of the admin's test mail").isTrue();
        assertThat(DeliverySteps.mailsTo(mail(), admin.email())).as("test mails to the admin").hasSize(1);
    }

    @Test
    @DisplayName("S4B-AC-19 without a session both endpoints answer 401")
    void signedOutIsUnauthenticated() {
        ApiClient anonymous = console();

        assertError(anonymous.post(Delivery.resendPath(DeliverySteps.NO_ROW)).send(), 401, "UNAUTHENTICATED");
        assertError(anonymous.post(Delivery.TEST_MAIL).send(), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("S4B-AC-19 an admin's request without the CSRF token is 403 on both endpoints, and no test mail "
            + "goes out")
    void withoutCsrfTokenIsForbidden() {
        Session admin = admin();

        assertError(admin.client().post(Delivery.resendPath(DeliverySteps.NO_ROW)).withoutCsrf().send(), 403,
                "CSRF_TOKEN_INVALID");
        assertError(admin.client().post(Delivery.TEST_MAIL).withoutCsrf().send(), 403, "CSRF_TOKEN_INVALID");

        awaitALaterMail(admin);
        assertThat(DeliverySteps.mailsTo(mail(), admin.email())).as("mails to the admin").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-19 on the portal host both endpoints are 404, for a customer and without a session")
    void portalHostHasNeitherEndpoint() {
        Cast c = cast();
        ApiClient anonymous = portal();

        assertError(c.customer().client().post(Delivery.resendPath(DeliverySteps.NO_ROW)).send(), 404, "NOT_FOUND");
        assertError(c.customer().client().post(Delivery.TEST_MAIL).send(), 404, "NOT_FOUND");
        assertError(anonymous.post(Delivery.resendPath(DeliverySteps.NO_ROW)).send(), 404, "NOT_FOUND");
        assertError(anonymous.post(Delivery.TEST_MAIL).send(), 404, "NOT_FOUND");

        awaitALaterMail(c.admin());
        assertThat(DeliverySteps.mailsTo(mail(), c.customer().email())).as("mails to the customer").isEmpty();
    }
}
