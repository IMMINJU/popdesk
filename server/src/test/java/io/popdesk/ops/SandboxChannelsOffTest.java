package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.notify.DeliverySteps;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A sandbox without a mail address turns all three channels off, although SMTP, the webhook, and the
 * outside copy are set up: a test mail is refused as for a channel that is off, and a channel that is
 * off raises no alert however long its receiving side refuses.
 */
class SandboxChannelsOffTest extends Delivery.SandboxNoAddress {

    private static final List<String> STALLED = List.of("MAIL_STALLED", "WEBHOOK_STALLED", "AUDIT_FORWARD_STALLED");

    @Test
    @DisplayName("S4B-AC-25 S4B-AC-27 in a sandbox without an address a test mail is 400 CHANNEL_DISABLED, "
            + "although SMTP is set up; nothing is sent and no audit entry is left")
    void testMailIsRefused() {
        Session tester = person(admin(), Role.ADMIN, "Tester");

        assertError(Delivery.testMailRequest(tester).send(), 400, "CHANNEL_DISABLED");

        assertThat(DeliverySteps.mailsTo(mail(), tester.email())).as("mails to the admin").isEmpty();
        assertThat(DeliverySteps.auditEntries(tester, DeliverySteps.TEST_MAIL_SENT, tester.accountId()))
                .as("TEST_MAIL_SENT entries of the admin").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-29 the channels a sandbox turned off raise no alert: with every receiving side refusing "
            + "for over an hour, the status shows the three channels off and no admin gets a stalled item")
    void channelsThatAreOffRaiseNoAlert() {
        Session admin = admin();
        Session watcher = person(admin, Role.ADMIN, "Watcher");
        Company company = fixtures.createCompany(admin);
        Session customer = person(admin, Role.CUSTOMER, "Customer", company.code());
        webhook().respondWith(500);
        smtp().refuseMessage("451 4.3.0 Try again later");
        fixtures.fileIssue(customer, company.code());
        admin.client().post("/api/console/invitations")
                .json(Map.of("email", Unique.email("unsent"), "name", Unique.text("Unsent"), "role", "AGENT"))
                .expect(201);

        JsonNode status = Delivery.status(admin);
        clock.advance(Duration.ofMinutes(61));
        Session later = fixtures.signInAgain(watcher);

        for (String channel : Delivery.CHANNELS.values()) {
            assertThat(status.path(channel).path("enabled").isBoolean()
                    && !status.path(channel).path("enabled").booleanValue()).as("%s is off in %s", channel, status)
                    .isTrue();
        }
        Wait.holds("no stalled item for the admin", Duration.ofSeconds(3),
                () -> STALLED.stream().allMatch(kind -> DeliverySteps.items(later, kind).isEmpty()));
    }
}
