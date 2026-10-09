package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * The table of webhook queue rows: the row a probe filing adds while the receiver answers 500,
     * holding its next attempt 1 second after the failure. The probe's row is sent and gone before
     * this returns.
     */
    private String webhookQueueTable(Scene s) {
        webhook().respondWith(500);
        Snapshot before = rows.snapshot();
        Issue probe = fixtures.fileIssue(s.customer(), s.code());
        Instant failedAt = clock.instant();
        awaitWebhooks(webhook(), probe.key(), 1);
        Row row = Wait.until("the queue row of the probe " + probe.key() + " with its next attempt 1 second later",
                () -> {
                    List<Row> added = rows.added(before).stream()
                            .filter(r -> !r.columnsAt(failedAt.plusSeconds(1)).isEmpty()).toList();
                    return added.size() == 1 ? added.getFirst() : null;
                });
        webhook().respondWith(204);
        clock.advance(Duration.ofSeconds(1));
        rows.awaitGone("the probe's webhook row deleted after the send worked", List.of(row));
        return row.table();
    }

    /** The answers to a filing and a comment by the customer, sent while a {@link CommitFailure} is in place. */
    private record Failed(ApiResponse filed, ApiResponse commented) {}

    private Failed fileAndComment(Scene s, Issue issue, String title, String comment) {
        ApiResponse filed = s.customer().client().post("/api/portal/issues")
                .json(Map.of("companyCode", s.code(), "title", title, "content", "Steps to reproduce.")).send();
        ApiResponse commented = s.customer().client().post("/api/portal/issues/" + issue.key() + "/comments")
                .json(Map.of("content", comment)).send();
        return new Failed(filed, commented);
    }

    /** Both requests failed with 500, left no row, filed and added nothing, and posted no webhook. */
    private void assertNothingStays(Scene s, Issue issue, Snapshot before, int webhooksOfIssue, String title,
            String comment, Failed failed) {
        for (ApiResponse r : List.of(failed.filed(), failed.commented())) {
            assertThat(r.status()).as(r.toString()).isEqualTo(500);
            assertThat(r.errorCode()).isEqualTo("INTERNAL_ERROR");
        }
        assertThat(rows.added(before)).as("rows left by the failed requests").isEmpty();
        assertThat(s.customer().client().get("/api/portal/issues").query("size", 100).expect(200).text())
                .as("the customer's issue list").doesNotContain(title);
        assertThat(s.customer().client().get("/api/portal/issues/" + issue.key() + "/comments").query("size", 100)
                .expect(200).text()).as("comments of " + issue.key()).doesNotContain(comment);
        Wait.holds("no webhook for the failed requests", Duration.ofSeconds(3),
                () -> webhook().containing(title).isEmpty()
                        && webhook().containing(issue.key()).size() == webhooksOfIssue);
    }

    @Test
    @DisplayName("S0-AC-66 when the webhook queue row fails at commit, a customer's filing and comment do not stay either, and nothing is posted")
    void failureOfWebhookRowRollsBackTheChange() {
        Scene s = steps.scene();
        String queue = webhookQueueTable(s);
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        int webhooksOfIssue = awaitWebhooks(webhook(), issue.key(), 1).size();
        String title = Unique.text("Rolled back filing");
        String comment = "Rolled back comment " + Unique.number();
        Snapshot before = rows.snapshot();
        Failed failed;
        try (CommitFailure failure = new CommitFailure(db)) {
            failure.failAtCommit(queue, "");
            failed = fileAndComment(s, issue, title, comment);
        }

        assertNothingStays(s, issue, before, webhooksOfIssue, title, comment, failed);
    }

    @Test
    @DisplayName("S0-AC-66 when a customer's filing or comment fails at commit after the webhook row was written, the webhook row goes with it and nothing is posted")
    void failureOfTheChangeRollsBackTheWebhookRow() {
        Scene s = steps.scene();
        String queue = webhookQueueTable(s);
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String probe = "Probe comment " + Unique.number();
        fixtures.comment(s.customer(), issue.key(), probe);
        int webhooksOfIssue = awaitWebhooks(webhook(), issue.key(), 2).size();
        List<String> issueTables = rows.tablesHolding(issue.json().path("title").asString(null));
        List<String> commentTables = rows.tablesHolding(probe);
        assertThat(issueTables).as("tables holding an issue's title").isNotEmpty().doesNotContain(queue);
        assertThat(commentTables).as("tables holding a comment's text").isNotEmpty().doesNotContain(queue);
        String title = Unique.text("Rolled back filing");
        String comment = "Rolled back comment " + Unique.number();
        Snapshot before = rows.snapshot();
        Failed failed;
        try (CommitFailure failure = new CommitFailure(db)) {
            issueTables.forEach(t -> failure.failAtCommit(t, title));
            commentTables.forEach(t -> failure.failAtCommit(t, comment));
            failed = fileAndComment(s, issue, title, comment);
        }

        assertNothingStays(s, issue, before, webhooksOfIssue, title, comment, failed);
    }
}
