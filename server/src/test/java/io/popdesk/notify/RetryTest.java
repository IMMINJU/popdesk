package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Retries of a failing send (D-39), through the webhook channel: the receiver answers 500, and the
 * test moves the app clock to each next attempt. The queue row is read with {@link Rows}; the
 * tests do not know its column names, so they look for a whole-number column that counts the
 * attempts and a time column that holds the next attempt.
 *
 * <p>A context of its own: these tests leave {@code failed} rows (and so operations alerts) behind
 * and move the clock by days. {@code POPDESK_TIMEZONE=UTC} is the default and only sets it apart.
 */
@TestPropertySource(properties = "POPDESK_TIMEZONE=UTC")
class RetryTest extends ConnectedAppTest {

    /** Waits after the 1st, 2nd, ... failure: 1 second doubling, at most 10 minutes. */
    private static final long[] WAITS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 600, 600};

    private NotifySteps steps;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
    }

    /** Files an issue while the receiver answers 500 and returns its queue row after the first failure. */
    private Caught fileWhileFailing(Session customer, String code) {
        webhook().respondWith(500);
        Snapshot before = rows.snapshot();
        Issue issue = fixtures.fileIssue(customer, code);
        Instant firstFailure = clock.instant();
        awaitWebhooks(webhook(), issue.key(), 1);
        Row row = Wait.until("the queue row of " + issue.key() + " with its next attempt 1 second later",
                () -> {
                    List<Row> added = rows.added(before).stream()
                            .filter(r -> !r.columnsAt(firstFailure.plusSeconds(1)).isEmpty()).toList();
                    return added.size() == 1 ? added.getFirst() : null;
                });
        return new Caught(issue, row, firstFailure);
    }

    private record Caught(Issue issue, Row row, Instant firstFailure) {}

    @Test
    @DisplayName("S0-AC-68 a failing send waits 1, 2, 4 ... seconds between attempts, at most 10 minutes, and counts the attempts")
    void backoffDoublesUpToTenMinutes() {
        Scene s = steps.scene();
        Caught caught = fileWhileFailing(s.customer(), s.code());
        String key = caught.issue().key();
        Set<String> attemptColumns = new HashSet<>(caught.row().columnsEqualTo(1));
        Set<String> nextColumns = new HashSet<>(caught.row().columnsAt(caught.firstFailure().plusSeconds(1)));

        for (int i = 0; i < WAITS.length; i++) {
            int attempts = i + 1;
            Instant failedAt = clock.instant();
            Instant next = failedAt.plusSeconds(WAITS[i]);
            Row now = Wait.until("attempt " + attempts + " of " + key + " recorded with its next attempt at " + next,
                    () -> {
                        Row r = rows.reread(caught.row());
                        return r != null && !r.columnsAt(next).isEmpty() && !r.columnsEqualTo(attempts).isEmpty()
                                ? r : null;
                    });
            attemptColumns.retainAll(now.columnsEqualTo(attempts));
            nextColumns.retainAll(now.columnsAt(next));
            assertThat(attemptColumns).as("a column counting the attempts, after attempt " + attempts + ": " + now)
                    .isNotEmpty();
            assertThat(nextColumns).as("a column holding the next attempt, after attempt " + attempts + ": " + now)
                    .isNotEmpty();
            assertThat(webhook().containing(key)).as("sends of " + key).hasSize(attempts);

            boolean checkNotEarly = i == 0 || (WAITS[i] == 600 && WAITS[i - 1] != 600);
            if (checkNotEarly) {
                clock.advance(Duration.ofSeconds(WAITS[i]).minusMillis(1));
                Wait.holds("no attempt " + (attempts + 1) + " of " + key + " before " + next, Duration.ofMillis(2500),
                        () -> webhook().containing(key).size() == attempts);
                clock.advance(Duration.ofMillis(1));
            } else {
                clock.advance(Duration.ofSeconds(WAITS[i]));
            }
            awaitWebhooks(webhook(), key, attempts + 1);
        }

        webhook().respondWith(204);
        rows.awaitGone("the row of " + key + " deleted after the send that worked", List.of(caught.row()));
    }

    @Test
    @DisplayName("S0-AC-68 an attempt that fails 24 hours after the first failure leaves the row failed, and it is not sent again")
    void failedAfterTwentyFourHours() {
        Scene s = steps.scene();
        Caught caught = fileWhileFailing(s.customer(), s.code());
        String key = caught.issue().key();

        clock.advance(Duration.between(clock.instant(), caught.firstFailure().plus(Duration.ofHours(24)).minusSeconds(1)));
        awaitWebhooks(webhook(), key, 2);
        Instant lastBefore = clock.instant();
        Row stillQueued = Wait.until("attempt 2 of " + key + ", 1 second before 24 hours, scheduled again", () -> {
            Row r = rows.reread(caught.row());
            return r != null && !r.columnsAt(lastBefore.plusSeconds(2)).isEmpty() ? r : null;
        });
        Session admin = fixtures.signInAdmin();
        assertThat(failedIds(admin)).as("failed rows 1 second before 24 hours").doesNotContain(stillQueued.key());

        clock.advance(Duration.ofSeconds(2));
        Wait.until("the row of " + key + " listed as failed", () -> failedIds(admin).contains(caught.row().key())
                ? key : null);

        int sends = webhook().containing(key).size();
        webhook().respondWith(204);
        clock.advance(Duration.ofHours(1));
        Wait.holds("no send of " + key + " after it failed", Duration.ofSeconds(3),
                () -> webhook().containing(key).size() == sends);
        assertThat(rows.exists(caught.row())).as("the failed row is kept").isTrue();
    }

    /** IDs in {@code GET /api/console/system/failed-deliveries} of the webhook channel, as text. */
    private static List<String> failedIds(Session admin) {
        JsonNode items = admin.client().get("/api/console/system/failed-deliveries").query("size", 100).expect(200)
                .json().path("items");
        if (!items.isArray()) {
            throw new AssertionError("No items array in failed-deliveries");
        }
        return items.values().stream()
                .filter(n -> "WEBHOOK".equals(n.path("channel").asString(null)))
                .map(n -> String.valueOf(n.path("id").asLong(-1)))
                .toList();
    }

    @Test
    @DisplayName("S0-AC-68 a failed row is deleted 7 days after it failed; a younger failed row stays")
    void failedRowsDeletedAfterSevenDays() {
        Scene s = steps.scene();
        Instant start = clock.instant();
        Caught older = fileWhileFailing(s.customer(), s.code());
        Session admin = makeFailed(older);

        clock.advance(Duration.ofDays(2));
        Session customer = fixtures.signInAgain(s.customer());
        Caught younger = fileWhileFailing(customer, s.code());
        admin = makeFailed(younger);

        // The older row failed about 1 day after start, the younger about 4 days after; both were made
        // at least 1 day before they failed. At start + 8 days and 1 minute only the older row is past
        // 7 days, whether counted from when it failed or from when it was made.
        clock.advance(Duration.between(clock.instant(), start.plus(Duration.ofDays(8)).plusSeconds(60)));

        rows.awaitGone("the older failed row deleted by the cleanup", List.of(older.row()), Duration.ofSeconds(70));
        assertThat(rows.exists(younger.row())).as("the younger failed row is kept").isTrue();
        assertThat(failedIds(admin)).contains(younger.row().key()).doesNotContain(older.row().key());
    }

    /** Moves the clock past 24 hours after the first failure and waits until the row is failed. */
    private Session makeFailed(Caught caught) {
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        Session admin = fixtures.signInAdmin();
        Wait.until("the row of " + caught.issue().key() + " listed as failed",
                () -> failedIds(admin).contains(caught.row().key()) ? caught : null);
        return admin;
    }
}
