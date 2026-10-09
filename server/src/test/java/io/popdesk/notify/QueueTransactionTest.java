package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A request that fails leaves no queue row and sends nothing; the row is part of the request's
 * transaction. The tests look at every table ({@link Rows}), since the queue table's name is not
 * fixed.
 */
class QueueTransactionTest extends ConnectedAppTest {

    private NotifySteps steps;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
    }

    @Test
    @DisplayName("S0-AC-66 an invitation that fails (400, 409) adds no row anywhere and sends no mail")
    void failedInvitationAddsNoRow() {
        Scene s = steps.scene();
        String fresh = Unique.email("unsent");
        Snapshot before = rows.snapshot();

        ApiResponse noCompany = s.admin().client().post("/api/console/invitations")
                .json(Map.of("email", fresh, "name", "Unsent", "role", "CUSTOMER")).send();
        ApiResponse taken = s.admin().client().post("/api/console/invitations")
                .json(Map.of("email", s.customer().email(), "name", "Again", "role", "AGENT")).send();

        assertThat(noCompany.status()).as(noCompany.toString()).isEqualTo(400);
        assertThat(noCompany.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(taken.status()).as(taken.toString()).isEqualTo(409);
        assertThat(taken.errorCode()).isEqualTo("EMAIL_TAKEN");
        assertThat(rows.added(before)).as("rows added by failed invitations").isEmpty();
        int mailsToCustomer = mail().messagesTo(s.customer().email()).size();
        Wait.holds("no mail for the failed invitations", Duration.ofSeconds(3),
                () -> mail().messagesTo(fresh).isEmpty()
                        && mail().messagesTo(s.customer().email()).size() == mailsToCustomer);
    }

    @Test
    @DisplayName("S0-AC-66 a comment that fails (400) adds no row anywhere, posts no webhook, and mails nobody")
    void failedCommentAddsNoRow() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        awaitWebhooks(webhook(), issue.key(), 1);
        Snapshot before = rows.snapshot();

        ApiResponse byCustomer = s.customer().client().post("/api/portal/issues/" + issue.key() + "/comments")
                .json(Map.of("content", "x".repeat(4001))).send();
        ApiResponse byAgent = s.agent().client().post("/api/console/issues/" + issue.key() + "/comments")
                .json(Map.of("content", "")).send();

        for (ApiResponse r : List.of(byCustomer, byAgent)) {
            assertThat(r.status()).as(r.toString()).isEqualTo(400);
            assertThat(r.errorCode()).isEqualTo("INVALID_INPUT");
        }
        assertThat(rows.added(before)).as("rows added by failed comments").isEmpty();
        Wait.holds("no webhook or mail for the failed comments", Duration.ofSeconds(3),
                () -> webhook().containing(issue.key()).size() == 1
                        && NotifySteps.notifications(mail(), s.customer().email()).isEmpty());
    }
}
