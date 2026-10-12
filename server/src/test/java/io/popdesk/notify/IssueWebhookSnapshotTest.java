package io.popdesk.notify;

import static io.popdesk.notify.WebhookSnapshots.assertMatches;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ConnectedAppTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The webhook messages of slice 3 in the default language, {@code en} (S3-AC-74, {@link IssueWebhookSnapshots}). */
class IssueWebhookSnapshotTest extends ConnectedAppTest {

    @Test
    @DisplayName("S3-AC-74 the English webhook messages for a resolved, reopened, and closed issue and a changed assignee match the snapshots")
    void englishMessages() {
        IssueWebhookSnapshots.bodies(fixtures, new IssueFlow(fixtures, clock), webhook(),
                new NotifySteps(fixtures).scene()).forEach((name, body) -> assertMatches("en-" + name, body));
    }
}
