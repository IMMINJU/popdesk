package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code POPDESK_SANDBOX=false} written out, with a {@code POPDESK_SANDBOX_MAIL_TO} left over
 * ({@link Delivery.PlainApp}): the app is no sandbox, as without the keys. An app that took any value
 * of the key for "sandbox" would fail here. Mail and webhook without the keys are the tests of the
 * earlier slices.
 */
class SandboxOffTest extends Delivery.PlainApp {

    @Test
    @DisplayName("S4B-AC-09, S4B-AC-06 with POPDESK_SANDBOX=false mail goes to the person and the webhook is "
            + "posted as before, the status says no sandbox, and the leftover address is not read")
    void falseIsNoSandbox() {
        Cast c = cast();
        String key = fixtures.fileIssue(c.customer(), c.code()).key();

        answer(c.agent(), key, 1);

        ReceivedMail m = NotifySteps.awaitNotification(mail(), c.customer().email());
        assertThat(m.subject()).as("subject").doesNotContainIgnoringCase("sandbox").contains(key);
        webhook().await("the new issue " + key, r -> !Delivery.isAuditCopy(r) && r.text().contains(key));
        for (String address : List.of(Delivery.SANDBOX_TO, Delivery.UNREAD_SANDBOX_TO)) {
            assertThat(mail().messagesTo(address)).as("mail to %s", address).isEmpty();
            assertThat(Delivery.foundIn(log.all(), address)).as("%s in the whole log", address).isEmpty();
        }
        Session admin = admin();
        JsonNode status = Delivery.status(admin);
        assertThat(status.path("sandbox").isBoolean() && !status.path("sandbox").asBoolean())
                .as("sandbox is false in %s", status).isTrue();
        assertThat(status.path("sandboxMailToSet").isBoolean() && !status.path("sandboxMailToSet").asBoolean())
                .as("sandboxMailToSet is false in %s", status).isTrue();
        for (String channel : Delivery.CHANNELS.values()) {
            assertThat(status.path(channel).path("enabled").asBoolean(false)).as("%s.enabled", channel).isTrue();
        }
    }
}
