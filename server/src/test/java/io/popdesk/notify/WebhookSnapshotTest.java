package io.popdesk.notify;

import static io.popdesk.notify.WebhookSnapshots.assertMatches;

import io.popdesk.support.ConnectedAppTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The webhook messages in the default language, {@code en} (S0-AC-75, {@link WebhookSnapshots}). */
class WebhookSnapshotTest extends ConnectedAppTest {

    @Test
    @DisplayName("S0-AC-75 the English webhook messages for a new issue and a customer comment match the snapshots")
    void englishMessages() {
        List<String> bodies = WebhookSnapshots.bodies(fixtures, webhook(), new NotifySteps(fixtures).scene());

        assertMatches("en-issue-created.json", bodies.get(0));
        assertMatches("en-customer-comment.json", bodies.get(1));
    }
}
