package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.notify.TableActivity.Counts;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Without {@code NOTIFY_WEBHOOK_URL} (setup "bare") the changes of slice 3 that would post a webhook
 * put nothing in the send queue, the way {@link NoWebhookTest} shows it for a new issue: no row
 * stays in the table the mails went through, and no other table gets a row that is deleted again.
 *
 * <p>Since slice 4 an agent's RESOLVED queues one mail to the customer who filed (S4-AC-48), which
 * without SMTP is a queue row handled at once. None of the other four changes sends a mail: the
 * customer's reopening and closing (S4-AC-48), assigning oneself (S4-AC-49), clearing the assignee.
 * So at most one row may come and go in the queue; each of the five changes would post a webhook, and
 * their rows would make it more.
 */
class NoIssueWebhookTest extends BareAppTest {

    private NotifySteps steps;
    private IssueFlow flow;
    private Rows rows;
    private TableActivity activity;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        flow = new IssueFlow(fixtures, clock);
        rows = new Rows(db);
        activity = new TableActivity(db);
    }

    @Test
    @DisplayName("S3-AC-73 without NOTIFY_WEBHOOK_URL resolving, reopening, closing, and setting and clearing the "
            + "assignee add no webhook row to the queue: at most the one row of the mail for RESOLVED (S4-AC-48)")
    void noWebhookRows() {
        Map<String, Counts> beforeScene = activity.counts();
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        flow.changeStatus(s.agent(), issue.key(), "IN_PROGRESS");
        // The scene's two invitation mails were queued and handled; wait until the statistics show both
        // rows inserted and deleted, so that they do not count as rows of the requests below. The table
        // they went through is the send queue: one that only had rows deleted meanwhile is not it.
        Set<String> queue = Wait.until("the scene's two mail rows counted as inserted and deleted", Duration.ofSeconds(25), () -> {
            Set<String> tables = TableActivity.insertedAndDeleted(beforeScene, activity.counts()).entrySet().stream()
                    .filter(e -> e.getValue().inserted() >= 2 && e.getValue().deleted() >= 2)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            return tables.isEmpty() ? null : tables;
        });
        Snapshot before = rows.snapshot();
        Map<String, Counts> countsBefore = activity.counts();

        flow.changeStatus(s.agent(), issue.key(), "RESOLVED");
        flow.reopen(s.customer(), issue.key());
        flow.assign(s.agent(), issue.key(), s.agent().accountId());
        flow.unassign(s.admin(), issue.key());
        flow.close(s.customer(), issue.key());

        Wait.holds("no table but the send queue gets a row that is deleted again, and the queue at most the one "
                + "mail row", Duration.ofSeconds(12), () -> {
                    Map<String, Long> deleted = TableActivity.deleted(countsBefore, activity.counts());
                    return queue.containsAll(deleted.keySet())
                            && deleted.values().stream().mapToLong(Long::longValue).sum() <= 1;
                });
        List<Row> added = rows.added(before);
        assertThat(added).as("rows added by the five changes: their history entries").hasSizeGreaterThanOrEqualTo(5);
        assertThat(added).as("rows added by the five changes, in the send queue %s", queue)
                .noneMatch(r -> queue.contains(r.table()));
    }
}
