package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.notify.DeliverySteps;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the pages of the system status never show (OPS-3, TM-49): the SMTP password, the webhook URL,
 * and the address and the token of the outside copy of the audit log. Each is looked for as it was
 * set, as Base64, percent-encoded, and JSON-escaped.
 */
class DeliverySecretsTest extends Delivery.PlainApp {

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
    @DisplayName("S4B-AC-31 the system status, the failed list with a webhook row and an outside copy row in it, "
            + "and the answers of a test "
            + "mail that went out, was refused, and found no server hold no SMTP password, webhook URL, or "
            + "address and token of the outside copy")
    void answersHoldNoSecret() {
        Cast c = cast();
        Session tester = person(c.admin(), Role.ADMIN, "Tester");
        DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        assertThat(failAuditCopies(() -> fixtures.createCompany(admin()))).as("failed rows of the outside copy")
                .isNotEmpty();
        Session admin = fixtures.signInAgain(tester);
        Map<String, String> answers = new LinkedHashMap<>();

        answers.put("system status", admin.client().get(Delivery.STATUS).expect(200).text());
        answers.put("failed list", admin.client().get(Delivery.FAILED).query("size", 100).expect(200).text());
        answers.put("test mail that went out", Delivery.testMailRequest(admin).expect(200).text());
        smtp().refuseLogin("535 5.7.8 Authentication failed");
        answers.put("test mail refused", Delivery.testMailRequest(admin).expect(200).text());
        smtp().drop();
        answers.put("test mail without a server", Delivery.testMailRequest(admin).expect(200).text());

        assertThat(Delivery.failedRows(admin).stream().map(n -> n.path("channel").asString("")).distinct().toList())
                .as("channels of the rows of the failed list").contains("WEBHOOK", "AUDIT_FORWARD");
        String receiver = "127.0.0.1:" + webhook().port();
        List<String> secrets = List.of(MailServer.PASSWORD, webhook().url(), receiver, "/hook",
                "http://" + receiver + Delivery.AUDIT_COPY_PATH, Delivery.AUDIT_COPY_PATH, Delivery.AUDIT_COPY_TOKEN);
        answers.forEach((what, body) -> {
            assertThat(LogForms.foundIn(body, secrets)).as("secrets in the %s: %s", what, body).isEmpty();
            for (String secret : secrets) {
                assertThat(Delivery.foundIn(body, secret)).as("forms of %s in the %s: %s", secret, what, body)
                        .isEmpty();
            }
        });
    }
}
