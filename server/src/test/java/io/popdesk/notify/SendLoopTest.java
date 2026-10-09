package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.support.WebhookReceiver.Reply;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The send loop, seen through the webhook channel: the receiver answers 500 to keep a row in the
 * queue, and 204 to let it go. Queue rows are found by {@link Rows}: a row added by the request
 * whose next attempt time is one second after the first failure.
 */
class SendLoopTest extends ConnectedAppTest {

    @Autowired
    PostgreSQLContainer postgres;

    private NotifySteps steps;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
    }

    /** Files an issue while the receiver fails, waits for the first failed attempt, and returns its queue row. */
    private Caught fileWhileFailing(Scene s) {
        webhook().respondWith(500);
        Snapshot before = rows.snapshot();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Instant failedAt = clock.instant();
        awaitWebhooks(webhook(), issue.key(), 1);
        Row row = Wait.until("the queue row of " + issue.key() + " with its next attempt 1 second later",
                () -> {
                    List<Row> added = rows.added(before).stream()
                            .filter(r -> !r.columnsAt(failedAt.plusSeconds(1)).isEmpty()).toList();
                    return added.size() == 1 ? added.getFirst() : null;
                });
        return new Caught(issue, row);
    }

    private record Caught(Issue issue, Row row) {}

    @Test
    @DisplayName("S0-AC-67 a due row is sent by the loop on its own within 3 seconds and deleted once sent")
    void dueRowIsSentAndDeleted() {
        Scene s = steps.scene();
        Caught caught = fileWhileFailing(s);
        String key = caught.issue().key();

        webhook().respondWith(204);
        clock.advance(Duration.ofSeconds(1));

        Wait.until("the retry of " + key + " without any new request to the app", Duration.ofSeconds(3),
                () -> webhook().containing(key).size() >= 2 ? key : null);
        rows.awaitGone("the queue row of " + key + " deleted after a successful send", List.of(caught.row()));
        clock.advance(Duration.ofHours(1));
        Wait.holds("no more sends of " + key + " after success", Duration.ofSeconds(3),
                () -> webhook().containing(key).size() == 2);
    }

    @Test
    @DisplayName("S0-AC-67 the loop is woken right after the commit: most sends start within 300 ms, not on the next 1 second tick")
    void loopIsWokenAfterCommit() {
        Scene s = steps.scene();
        int fast = 0;
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Issue issue = fixtures.fileIssue(s.customer(), s.code());
            Instant answered = Instant.now();
            Received received = awaitWebhooks(webhook(), issue.key(), 1).getFirst();
            long millis = Duration.between(answered, received.receivedAt()).toMillis();
            latencies.add(millis);
            if (millis < 300) {
                fast++;
            }
        }

        assertThat(fast).as("sends that started within 300 ms of the 201, latencies " + latencies + " ms")
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("S0-AC-67 the loop skips a row another transaction has locked and sends the rows after it")
    void loopSkipsLockedRows() throws SQLException {
        Scene s = steps.scene();
        Caught older = fileWhileFailing(s);
        Caught newer = fileWhileFailing(s);

        try (Connection lock = DriverManager.getConnection(db.jdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            lock.setSchema(db.schema());
            lock.setAutoCommit(false);
            lockRow(lock, older.row());

            webhook().respondWith(204);
            clock.advance(Duration.ofSeconds(5));

            Wait.until("the newer row sent while the older one is locked",
                    () -> webhook().containing(newer.issue().key()).size() >= 2 ? newer : null);
            Wait.holds("the locked row not sent", Duration.ofSeconds(3),
                    () -> webhook().containing(older.issue().key()).size() == 1);
            assertThat(rows.exists(older.row())).as("the locked row is still queued").isTrue();
            lock.rollback();
        }

        awaitWebhooks(webhook(), older.issue().key(), 2);
        rows.awaitGone("the older row deleted after its lock was released", List.of(older.row()));
    }

    private static void lockRow(Connection connection, Row row) throws SQLException {
        String sql = "select 1 from " + Rows.quote(row.table()) + " where " + keyMatch(connection, row)
                + " for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.key());
            if (!statement.executeQuery().next()) {
                throw new AssertionError("Could not lock " + row);
            }
        }
    }

    /** {@code <primary key as text> = ?}, read from the catalog on the locking connection. */
    private static String keyMatch(Connection connection, Row row) throws SQLException {
        String sql = "select string_agg(quote_ident(a.attname) || '::text', ', ' order by k.n) "
                + "from pg_constraint c cross join lateral unnest(c.conkey) with ordinality k(attnum, n) "
                + "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum "
                + "where c.contype = 'p' and c.conrelid = (quote_ident(current_schema()) || '.' || quote_ident(?))::regclass";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.table());
            var rs = statement.executeQuery();
            if (!rs.next() || rs.getString(1) == null) {
                throw new AssertionError("No primary key on " + row.table());
            }
            String columns = rs.getString(1);
            return columns.contains(",") ? "concat_ws('|', " + columns + ") = ?" : columns + " = ?";
        }
    }

    @Test
    @DisplayName("S0-AC-67 the loop sends outside a transaction: no database connection waits in a transaction while the receiver is slow")
    void sendsOutsideTransaction() {
        Scene s = steps.scene();
        CountDownLatch release = new CountDownLatch(1);
        webhook().respond(r -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.status(204);
        });
        try {
            Issue issue = fixtures.fileIssue(s.customer(), s.code());
            awaitWebhooks(webhook(), issue.key(), 1);

            Wait.holds("no connection idle in a transaction for over 500 ms while the webhook is being sent",
                    Duration.ofSeconds(3), () -> idleInTransaction() == 0);
        } finally {
            release.countDown();
        }
    }

    private long idleInTransaction() {
        return db.count("select count(*) from pg_stat_activity where datname = current_database() "
                + "and pid <> pg_backend_pid() and state like 'idle in transaction%' "
                + "and state_change < clock_timestamp() - interval '500 milliseconds'");
    }

    @Test
    @DisplayName("S0-AC-67 due rows are sent oldest first")
    void oldestFirst() {
        Scene s = steps.scene();
        List<Caught> caught = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            caught.add(fileWhileFailing(s));
            // Less than the first wait of 1 second, so no row is due again before all three are queued.
            clock.advance(Duration.ofMillis(100));
        }

        webhook().respondWith(204);
        clock.advance(Duration.ofSeconds(10));

        List<Instant> resent = new ArrayList<>();
        for (Caught c : caught) {
            resent.add(awaitWebhooks(webhook(), c.issue().key(), 2).stream()
                    .map(Received::receivedAt).max(Comparator.naturalOrder()).orElseThrow());
        }
        assertThat(resent).as("times of the second send of each issue, in filing order").isSorted();
    }
}
