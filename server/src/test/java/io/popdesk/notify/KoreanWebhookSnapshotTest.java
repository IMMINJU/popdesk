package io.popdesk.notify;

import static io.popdesk.notify.WebhookSnapshots.assertMatches;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The webhook messages with {@code POPDESK_LOCALE=ko} (S0-AC-75, {@link WebhookSnapshots}). */
class KoreanWebhookSnapshotTest extends GatedMailTest {

    @Test
    @DisplayName("S0-AC-75 the Korean webhook messages for a new issue and a customer comment match the snapshots")
    void koreanMessages() {
        List<String> bodies = WebhookSnapshots.bodies(fixtures, webhook(), new NotifySteps(fixtures).scene());

        assertMatches("ko-issue-created.json", bodies.get(0));
        assertMatches("ko-customer-comment.json", bodies.get(1));
    }
}
