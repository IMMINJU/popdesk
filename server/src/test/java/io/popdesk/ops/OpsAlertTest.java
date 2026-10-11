package io.popdesk.ops;

import static io.popdesk.ops.OpsChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Operational alerts (OPS-1): a failed outbox row and attachments over 80% of the quota put an item
 * in every admin's inbox. While it is not resolved (no failed row left, attachments back under 80%)
 * there is one more item of that kind every 24 hours of the app's clock, and no more (tasks.md
 * section 6).
 *
 * <p>A context of its own: a small quota (1MB), and alerts depend on what happened earlier in the
 * same database. A failed row is deleted 7 days after it failed (S0-AC-68), and until then the
 * failure is not resolved and repeats every 24 hours. So each test first moves the clock eight days
 * on and waits until no failed row is left: every test starts from a resolved state. It then uses a
 * new admin whose inbox starts empty.
 *
 * <p>An outbox row fails when the webhook keeps answering 500 for 24 hours after its first failure
 * (S0-AC-68): the test lets the first attempt fail, then moves the clock 25 hours on.
 */
@TestPropertySource(properties = "POPDESK_ATTACHMENT_QUOTA=1MB")
class OpsAlertTest extends ConnectedAppTest {

    static final String INBOX = "/api/console/notifications";

    @Autowired
    Environment environment;

    private Session admin;
    private Company company;
    private Session customer;

    @BeforeEach
    void freshDay() {
        clock.advance(Duration.ofDays(8));
        admin = fixtures.signInAdmin();
        Wait.untilAsserted("no failed row left from earlier tests", () -> {
            JsonNode status = admin.client().get(SystemStatusTest.STATUS).expect(200).json();
            assertThat(status.path("webhook").path("failed").asLong(-1)).as("webhook.failed").isZero();
            assertThat(status.path("mail").path("failed").asLong(-1)).as("mail.failed").isZero();
        });
        company = fixtures.createCompany(admin);
        customer = fixtures.customer(admin, company.code());
    }

    /** The failed webhook rows created at {@code createdAt}, from the failed list. */
    private static List<JsonNode> failedRowsCreatedAt(Session session, Instant createdAt) {
        return OpsChecks.allItems(session.client(), SystemStatusTest.FAILED).stream()
                .filter(n -> "WEBHOOK".equals(n.path("channel").asString(null)))
                .filter(n -> n.path("createdAt").isString()
                        && OpsChecks.instant(n.path("createdAt"), "createdAt").equals(createdAt))
                .toList();
    }

    /** Files issues while the webhook answers 500, and waits until each had its first attempt. */
    private List<Issue> issuesWithFailingWebhook(int n) {
        webhook().respondWith(500);
        List<Issue> issues = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Issue issue = fixtures.fileIssue(customer, company.code());
            webhook().await("a first attempt for " + issue.key(), r -> r.text().contains(issue.key()));
            issues.add(issue);
        }
        return issues;
    }

    /** Moves past the 24 hours of retries; every session ends, so admins sign in again. */
    private void giveUpRetrying() {
        clock.advance(Duration.ofHours(25));
    }

    private static List<JsonNode> items(Session session, String kind) {
        return OpsChecks.allItems(session.client(), INBOX).stream()
                .filter(n -> kind.equals(n.path("kind").asString(null)))
                .toList();
    }

    private static List<JsonNode> awaitItems(Session session, String kind) {
        return Wait.until(kind + " in the inbox of " + session.email(), () -> {
            List<JsonNode> found = items(session, kind);
            return found.isEmpty() ? null : found;
        });
    }

    private long unread(Session session) {
        return session.client().get(INBOX + "/unread-count").expect(200).jsonLong("count");
    }

    @Test
    @DisplayName("S0-AC-80 a failed outbox row puts DELIVERY_FAILED in every admin's inbox and none in an agent's")
    void failedRowAlertsEveryAdmin() {
        Session second = fixtures.admin(admin);
        Session agent = fixtures.agent(admin, company.code());
        issuesWithFailingWebhook(1);

        giveUpRetrying();
        Session first = fixtures.signInAdmin();
        second = fixtures.signInAgain(second);
        agent = fixtures.signInAgain(agent);

        assertThat(awaitItems(second, "DELIVERY_FAILED")).as("items of the second admin").hasSize(1);
        assertThat(awaitItems(first, "DELIVERY_FAILED")).as("items of the first admin").isNotEmpty();
        assertThat(OpsChecks.allItems(agent.client(), INBOX)).as("inbox of an agent").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-80 two rows failing on the same day give one DELIVERY_FAILED item")
    void oneAlertADay() {
        Session second = fixtures.admin(admin);
        Instant filedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        issuesWithFailingWebhook(2);

        giveUpRetrying();
        Session again = fixtures.signInAgain(second);

        Wait.untilAsserted("both rows of this test failed", () -> assertThat(failedRowsCreatedAt(again, filedAt))
                .hasSize(2));
        Wait.holds("one DELIVERY_FAILED item", Duration.ofSeconds(3),
                () -> items(again, "DELIVERY_FAILED").size() == 1);
    }

    @Test
    @DisplayName("S0-AC-80 an admin sees the unread count, marks an item read, and the count goes down")
    void markRead() {
        Session second = fixtures.admin(admin);
        issuesWithFailingWebhook(1);
        giveUpRetrying();
        Session again = fixtures.signInAgain(second);
        JsonNode item = awaitItems(again, "DELIVERY_FAILED").getFirst();
        long before = unread(again);
        assertThat(item.path("readAt").isNull()).as("readAt of a new item %s", item).isTrue();
        assertThat(before).as("unread count").isGreaterThanOrEqualTo(1);

        again.client().post(INBOX + "/" + item.path("id").asLong() + "/read").expect(204);
        again.client().post(INBOX + "/" + item.path("id").asLong() + "/read").expect(204);

        assertThat(unread(again)).as("unread count after reading").isEqualTo(before - 1);
        JsonNode read = items(again, "DELIVERY_FAILED").stream()
                .filter(n -> n.path("id").asLong() == item.path("id").asLong())
                .findFirst().orElseThrow(() -> new AssertionError("Item " + item + " is gone"));
        assertThat(OpsChecks.instant(read.path("readAt"), "readAt")).isEqualTo(clock.instant()
                .truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("S0-AC-80 an admin cannot mark another admin's item read: 404")
    void cannotReadOthersItem() {
        Session second = fixtures.admin(admin);
        issuesWithFailingWebhook(1);
        giveUpRetrying();
        Session again = fixtures.signInAgain(second);
        Session first = fixtures.signInAdmin();
        JsonNode item = awaitItems(again, "DELIVERY_FAILED").getFirst();

        assertError(first.client().post(INBOX + "/" + item.path("id").asLong() + "/read").send(), 404, "NOT_FOUND");
        assertThat(items(again, "DELIVERY_FAILED").getFirst().path("readAt").isNull()).as("still unread").isTrue();
    }

    @Test
    @DisplayName("S0-AC-80 a failed row not resolved gives one more DELIVERY_FAILED item 24 hours later")
    void repeatsWhileUnresolved() {
        Session second = fixtures.admin(admin);
        issuesWithFailingWebhook(1);
        giveUpRetrying();
        assertThat(awaitItems(fixtures.signInAgain(second), "DELIVERY_FAILED")).as("items on the first day")
                .hasSize(1);

        clock.advance(Duration.ofHours(25));
        Session again = fixtures.signInAgain(second);

        Wait.untilAsserted("a second DELIVERY_FAILED item", () -> assertThat(items(again, "DELIVERY_FAILED"))
                .hasSize(2));
    }

    @Test
    @DisplayName("S0-AC-80 attachments over 80% of the quota give ATTACHMENT_QUOTA_WARNING, one while they stay "
            + "over; after they go back under 80%, passing it again the same day gives another")
    void quotaWarningAgainAfterResolved() {
        Session second = fixtures.admin(admin);
        Issue issue = fixtures.fileIssue(customer, company.code());
        File big = new File("big.bin", "application/octet-stream", new byte[900_000]);

        Attachment first = fixtures.attach(customer, issue.key(), big);
        assertThat(awaitItems(second, "ATTACHMENT_QUOTA_WARNING")).as("items after the first upload").hasSize(1);
        Attachment small = fixtures.attach(customer, issue.key(), File.text("small.txt", "Still over 80%."));
        Wait.holds("one ATTACHMENT_QUOTA_WARNING item after another upload over 80%", Duration.ofSeconds(3),
                () -> items(second, "ATTACHMENT_QUOTA_WARNING").size() == 1);
        admin.client().delete("/api/console/issues/" + issue.key() + "/attachments/" + small.id()).expect(204);

        admin.client().delete("/api/console/issues/" + issue.key() + "/attachments/" + first.id()).expect(204);
        Wait.untilAsserted("the first upload deleted", () -> assertThat(OpsChecks.allItems(admin.client(),
                        "/api/console/issues/" + issue.key() + "/attachments"))
                .anySatisfy(a -> {
                    assertThat(a.path("id").asString("")).isEqualTo(first.id());
                    assertThat(a.path("deleted").asBoolean(false)).isTrue();
                }));
        ApiResponse again = customer.client().post("/api/portal/issues/" + issue.key() + "/attachments")
                .file("file", big.name(), big.contentType(), big.content()).expect(201);

        Wait.untilAsserted("a second ATTACHMENT_QUOTA_WARNING item", () -> assertThat(items(second,
                "ATTACHMENT_QUOTA_WARNING")).hasSize(2));
        admin.client().delete("/api/console/issues/" + issue.key() + "/attachments/" + again.jsonText("id"))
                .expect(204);
    }

    @Test
    @DisplayName("S0-AC-81 a failed webhook row is in the failed list: channel, creation time, error kind, no account")
    void failedRowListed() {
        Instant filedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        issuesWithFailingWebhook(1);
        giveUpRetrying();
        Session again = fixtures.signInAdmin();

        JsonNode row = Wait.until("the failed row filed at " + filedAt, () -> failedRowsCreatedAt(again, filedAt)
                .stream().findFirst().orElse(null));

        assertThat(OpsChecks.fieldNames(row))
                .isEqualTo(Set.of("id", "channel", "createdAt", "lastErrorKind", "recipientAccountId", "auditEntryId"));
        assertThat(row.path("lastErrorKind").asString("")).as("lastErrorKind").isNotBlank();
        assertThat(row.has("recipientAccountId") && row.path("recipientAccountId").isNull())
                .as("recipientAccountId of a webhook row").isTrue();
        assertThat(row.path("auditEntryId").isNull()).as("auditEntryId of a webhook row").isTrue();
        ApiResponse status = again.client().get(SystemStatusTest.STATUS).expect(200);
        assertThat(status.json().path("webhook").path("failed").asLong(-1)).as("webhook.failed").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("S0-AC-78 a failed webhook row shows in the failed gauge of the webhook channel")
    void failedGauge() {
        Prometheus prometheus = new Prometheus(environment);
        issuesWithFailingWebhook(1);
        giveUpRetrying();

        Wait.untilAsserted("the failed gauge", () -> assertThat(prometheus.scrape()
                .forChannel(Prometheus.Names.OUTBOX_FAILED, "webhook")
                .orElseThrow(() -> new AssertionError("no " + Prometheus.Names.OUTBOX_FAILED + " for webhook"))
                .value()).isGreaterThanOrEqualTo(1));
    }
}
