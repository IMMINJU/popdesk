package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The numbers that go with failed outbox rows: the totals of the failed list (OPS-3, COM-4) and the
 * count the cleanup of failed rows records (OPS-2). {@link OpsAlertTest} checks the items of the
 * list, and {@link CleanupRecordTest} that the job has a row; neither has a failed row to count.
 *
 * <p>This class sets the same key as {@link OpsAlertTest} and so shares its context and database. As
 * there, each test first moves the clock eight days on and waits until no failed row is left, and a
 * row fails when the webhook answers 500 and the clock moves 25 hours on (S0-AC-68).
 */
@TestPropertySource(properties = "POPDESK_ATTACHMENT_QUOTA=1MB")
class FailedDeliveryCountsTest extends ConnectedAppTest {

    private Session admin;
    private Company company;
    private Session customer;

    @BeforeEach
    void freshDay() {
        clock.advance(Duration.ofDays(8));
        admin = fixtures.signInAdmin();
        awaitFailed(admin, 0);
        company = fixtures.createCompany(admin);
        customer = fixtures.customer(admin, company.code());
    }

    @Test
    @DisplayName("S0-AC-81 the failed list gives the number of failed rows as totalElements and the pages they fill as totalPages")
    void failedListGivesTotals() {
        failWebhookRows(2);
        Session again = fixtures.signInAdmin();
        awaitFailed(again, 2);

        JsonNode page = again.client().get(SystemStatusTest.FAILED).query("size", 1).expect(200).json();

        assertThat(page.path("items").size()).as("failed rows on the first page").isEqualTo(1);
        assertThat(page.path("totalElements").asLong(-1)).as("totalElements with two failed rows").isEqualTo(2);
        assertThat(page.path("totalPages").asLong(-1)).as("totalPages, one row a page").isEqualTo(2);
    }

    @Test
    @DisplayName("S0-AC-82 the cleanup that deletes a failed row after 7 days records a count of at least one")
    void cleanupCountsDeletedRows() {
        failWebhookRows(1);
        awaitFailed(fixtures.signInAdmin(), 1);

        Instant due = clock.advance(Duration.ofDays(8)).truncatedTo(ChronoUnit.MICROS);
        awaitFailed(fixtures.signInAdmin(), 0);

        Row record = Wait.until("the record of " + CleanupRecordTest.OUTBOX + " for a run at or after " + due,
                Duration.ofSeconds(60), () -> recordSince(due));
        assertThat(count(record)).as("deleted rows in %s", record).isGreaterThanOrEqualTo(1);
    }

    /** Files issues while the webhook answers 500, waits for each first attempt, and moves past the 24 hours of retries. */
    private void failWebhookRows(int n) {
        webhook().respondWith(500);
        for (int i = 0; i < n; i++) {
            Issue issue = fixtures.fileIssue(customer, company.code());
            webhook().await("a first attempt for " + issue.key(), r -> r.text().contains(issue.key()));
        }
        clock.advance(Duration.ofHours(25));
    }

    private static void awaitFailed(Session session, long webhookRows) {
        Wait.untilAsserted(webhookRows + " failed webhook rows and no failed mail row", Duration.ofSeconds(60), () -> {
            JsonNode status = session.client().get(SystemStatusTest.STATUS).expect(200).json();
            assertThat(status.path("webhook").path("failed").asLong(-1)).as("webhook.failed").isEqualTo(webhookRows);
            assertThat(status.path("mail").path("failed").asLong(-1)).as("mail.failed").isZero();
        });
    }

    /** The job's one row when it holds a time at or after {@code since}, else null. */
    private Row recordSince(Instant since) {
        List<Row> rows = new AuditTrail(db).rowsContaining(CleanupRecordTest.OUTBOX).stream()
                .filter(r -> r.hasText(CleanupRecordTest.OUTBOX)).toList();
        if (rows.size() != 1 || rows.getFirst().times().stream().noneMatch(t -> !t.isBefore(since))) {
            return null;
        }
        return rows.getFirst();
    }

    /** The largest whole number in a column that is not a key (id, *_id, *Id), or -1. */
    private static long count(Row row) {
        long max = -1;
        for (Map.Entry<String, JsonNode> e : row.json().properties()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            boolean key = name.equals("id") || name.endsWith("_id") || e.getKey().endsWith("Id");
            if (!key && e.getValue().isIntegralNumber()) {
                max = Math.max(max, e.getValue().asLong());
            }
        }
        return max;
    }
}
