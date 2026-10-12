package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The test mail counts toward the 50 mails an hour one address gets (MAIL-6). In a sandbox every mail
 * goes to one address, so 49 invitations and one test mail fill the hour, and the next mail is not
 * sent. The setup moves the clock 61 minutes on before each test, so the count starts at none.
 */
class TestMailCountsTest extends Delivery.SandboxWithAddress {

    @AfterEach
    void leaveNoFailedRow() {
        startClean();
    }

    @Test
    @DisplayName("S4B-AC-13 a test mail is one of the 50 an hour its address gets: after 49 other mails and a "
            + "test mail, the next mail to the address is failed as RATE_LIMITED and not sent")
    void testMailIsCountedAgainstTheLimitOfItsAddress() {
        Session admin = admin();
        Session tester = person(admin, Role.ADMIN, "Tester");
        for (int i = 0; i < Delivery.MAIL_LIMIT - 2; i++) {
            invite(admin, Role.AGENT, Unique.email("filler"), Unique.text("Filler"), List.of());
        }

        JsonNode result = Delivery.testMail(tester);

        assertThat(result.path("ok").isBoolean() && result.path("ok").booleanValue()).as("ok of the test mail, the "
                + "50th of the hour: %s", result).isTrue();
        long over = admin.client().post("/api/console/invitations")
                .json(Map.of("email", Unique.email("over"), "name", Unique.text("Over"), "role", "AGENT"))
                .expect(201).jsonLong("accountId");
        JsonNode row = Delivery.awaitFailedRow(admin, "the failed mail row of account " + over,
                n -> "MAIL".equals(n.path("channel").asString(null)) && n.path("recipientAccountId").asLong(-1) == over);
        assertThat(row.path("lastErrorKind").asString(null)).as("lastErrorKind of %s", row).isEqualTo("RATE_LIMITED");
        assertThat(Delivery.redirected(mail(), over)).as("mails of the 51st at the sandbox address").isEmpty();
    }
}
