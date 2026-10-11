package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * When an operational alert counts as resolved (OPS-1): the delivery alert when no failed row is
 * left, the quota alert when the attachments are back under 80%. A resolved alert stops repeating,
 * and the next time it is raised the admins hear of it at once, however short a time ago they last
 * heard of the old one.
 *
 * <p>Shares the context of {@link OpsAlertTest} (same setup, quota 1MB) and starts each test the way
 * that class does: eight days on, no failed row left, a new admin with an empty inbox. Each test
 * deletes the attachments it made, so the total it leaves for the other class is what it found.
 */
@TestPropertySource(properties = "POPDESK_ATTACHMENT_QUOTA=1MB")
class AlertResolvedTest extends ConnectedAppTest {

    private static final String FAILED = "DELIVERY_FAILED";
    private static final String QUOTA = "ATTACHMENT_QUOTA_WARNING";

    private Session second;
    private Company company;
    private Session customer;

    @BeforeEach
    void freshDay() {
        clock.advance(Duration.ofDays(8));
        awaitNoFailedRow();
        Session admin = fixtures.signInAdmin();
        company = fixtures.createCompany(admin);
        customer = fixtures.customer(admin, company.code());
        second = fixtures.admin(admin);
    }

    private void awaitNoFailedRow() {
        Session current = fixtures.signInAdmin();
        Wait.untilAsserted("no failed row left", () -> {
            JsonNode status = current.client().get(SystemStatusTest.STATUS).expect(200).json();
            assertThat(status.path("webhook").path("failed").asLong(-1)).as("webhook.failed").isZero();
            assertThat(status.path("mail").path("failed").asLong(-1)).as("mail.failed").isZero();
        });
    }

    /** Moves the app's clock on. Sessions may end on the way, so the second admin signs in again. */
    private void advance(Duration duration) {
        clock.advance(duration);
        second = fixtures.signInAgain(second);
    }

    private List<JsonNode> items(String kind) {
        return OpsChecks.allItems(second.client(), OpsAlertTest.INBOX).stream()
                .filter(n -> kind.equals(n.path("kind").asString(null)))
                .toList();
    }

    private void awaitItems(String kind, int count, String when) {
        Wait.untilAsserted(count + " " + kind + " items " + when,
                () -> assertThat(items(kind)).as("%s items %s", kind, when).hasSize(count));
    }

    /** Files an issue while the webhook answers 500 and waits for its first attempt. */
    private Issue issueWithFailingWebhook() {
        webhook().respondWith(500);
        Issue issue = fixtures.fileIssue(customer, company.code());
        webhook().await("a first attempt for " + issue.key(), r -> r.text().contains(issue.key()));
        return issue;
    }

    @Test
    @DisplayName("S1A-AC-68 once no failed row is left the delivery alert is resolved: a row that fails 18 hours "
            + "after the admins last heard of the old one gives a new DELIVERY_FAILED item at once")
    void failedAlertIsRaisedAgainAfterResolved() {
        issueWithFailingWebhook();
        advance(Duration.ofHours(25));
        awaitItems(FAILED, 1, "when the first row failed");
        advance(Duration.ofDays(5).plusHours(12));
        awaitItems(FAILED, 2, "5.5 days later, the row still failed");
        advance(Duration.ofHours(18));
        Wait.holds("no third item 18 hours after the second", Duration.ofSeconds(2), () -> items(FAILED).size() == 2);
        customer = fixtures.signInAgain(customer);
        issueWithFailingWebhook();
        advance(Duration.ofHours(7));
        awaitItems(FAILED, 3, "25 hours after the second item, the first row still failed");

        // 7 days and an hour after the first row failed it is deleted; the second row is still retried.
        advance(Duration.ofHours(12));
        awaitNoFailedRow();
        Wait.holds("no item while no row is failed", Duration.ofSeconds(3), () -> items(FAILED).size() == 3);
        // 25 hours after its first attempt the second row fails: 18 hours after the third item.
        advance(Duration.ofHours(6));

        awaitItems(FAILED, 4, "when the second row failed, 18 hours after the third item");
    }

    @Test
    @DisplayName("S1A-AC-68 once no failed row is left the delivery alert stops repeating: 25 hours later there is "
            + "no new DELIVERY_FAILED item")
    void resolvedFailedAlertStopsRepeating() {
        issueWithFailingWebhook();
        advance(Duration.ofHours(25));
        awaitItems(FAILED, 1, "when the row failed");

        advance(Duration.ofDays(7).plusHours(2));
        awaitNoFailedRow();
        int settled = items(FAILED).size();
        assertThat(settled).as("items when the row is gone: the first, and at most one repeat").isBetween(1, 2);
        advance(Duration.ofHours(25));

        Wait.holds("no new item 25 hours after the alert was resolved", Duration.ofSeconds(3),
                () -> items(FAILED).size() == settled);
    }

    @Test
    @DisplayName("S1A-AC-68 once the attachments are back under 80% the quota alert stops repeating: 25 hours "
            + "later there is no new ATTACHMENT_QUOTA_WARNING item, and going over 80% again gives one at once")
    void resolvedQuotaAlertStopsRepeating() {
        Issue issue = fixtures.fileIssue(customer, company.code());
        File big = new File("big.bin", "application/octet-stream", new byte[900_000]);
        String attachments = "/api/console/issues/" + issue.key() + "/attachments";
        String first = fixtures.attach(customer, issue.key(), big).id();
        String again = null;
        try {
            awaitItems(QUOTA, 1, "after the upload over 80%");
            delete(attachments, first);
            first = null;

            advance(Duration.ofHours(25));
            Wait.holds("no new item 25 hours after the attachments went under 80%", Duration.ofSeconds(3),
                    () -> items(QUOTA).size() == 1);
            advance(Duration.ofHours(25));
            Wait.holds("still one item an hour past the second day", Duration.ofSeconds(2),
                    () -> items(QUOTA).size() == 1);
            customer = fixtures.signInAgain(customer);
            again = fixtures.attach(customer, issue.key(), big).id();

            awaitItems(QUOTA, 2, "after going over 80% again");
        } finally {
            for (String id : new String[] {first, again}) {
                if (id != null) {
                    delete(attachments, id);
                }
            }
        }
    }

    /** Deletes an attachment as the admin and waits until the list shows it deleted. */
    private void delete(String attachments, String id) {
        Session current = fixtures.signInAdmin();
        ApiResponse deleted = current.client().delete(attachments + "/" + id).send();
        assertThat(deleted.status()).as("%s: %s", deleted, deleted.snippet()).isEqualTo(204);
        Wait.untilAsserted("attachment " + id + " deleted", () -> assertThat(OpsChecks.allItems(current.client(),
                attachments)).anySatisfy(a -> {
                    assertThat(a.path("id").asString("")).isEqualTo(id);
                    assertThat(a.path("deleted").asBoolean(false)).isTrue();
                }));
    }
}
