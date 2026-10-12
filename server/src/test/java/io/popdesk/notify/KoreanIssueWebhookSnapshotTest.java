package io.popdesk.notify;

import static io.popdesk.notify.WebhookSnapshots.assertMatches;

import io.popdesk.issue.IssueFlow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The webhook messages of slice 3 with {@code POPDESK_LOCALE=ko} (S3-AC-74, {@link IssueWebhookSnapshots}). */
class KoreanIssueWebhookSnapshotTest extends GatedMailTest {

    @Test
    @DisplayName("S3-AC-74 the Korean webhook messages for a resolved, reopened, and closed issue and a changed assignee match the snapshots")
    void koreanMessages() {
        IssueWebhookSnapshots.bodies(fixtures, new IssueFlow(fixtures, clock), webhook(),
                new NotifySteps(fixtures).scene()).forEach((name, body) -> assertMatches("ko-" + name, body));
    }
}
