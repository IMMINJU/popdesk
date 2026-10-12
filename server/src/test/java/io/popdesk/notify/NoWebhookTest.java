package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.notify.TableActivity.Counts;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Unique;
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
 * Without {@code NOTIFY_WEBHOOK_URL} (setup "bare") filing an issue and a customer's comment put
 * nothing in the send queue: they add no row to the table the mails went through, one row holds the
 * title and one the comment, and no table gets a row that is deleted again (a queue row handled at
 * once). Rows of other tables are not this test's: filing also writes the issue's history (S3-AC-61).
 */
class NoWebhookTest extends BareAppTest {

    private NotifySteps steps;
    private Rows rows;
    private TableActivity activity;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
        activity = new TableActivity(db);
    }

    @Test
    @DisplayName("S0-AC-77 without NOTIFY_WEBHOOK_URL a new issue and a customer's comment add no queue row")
    void noWebhookRows() {
        Map<String, Counts> beforeScene = activity.counts();
        Scene s = steps.scene();
        String title = Unique.text("Unhooked");
        String content = "Unhooked comment " + Unique.number();
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

        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(title));
        fixtures.comment(s.customer(), issue.key(), content);

        Wait.holds("no table gets a row that is deleted again", Duration.ofSeconds(12),
                () -> TableActivity.deleted(countsBefore, activity.counts()).isEmpty());
        List<Row> added = rows.added(before);
        List<Row> issueRows = added.stream().filter(r -> !rows.columnsContaining(r, title).isEmpty()).toList();
        List<Row> commentRows = added.stream().filter(r -> !rows.columnsContaining(r, content).isEmpty()).toList();
        assertThat(issueRows).as("added rows holding the title").hasSize(1);
        assertThat(commentRows).as("added rows holding the comment").hasSize(1);
        assertThat(added).as("rows added by filing and commenting, in the send queue %s", queue)
                .noneMatch(r -> queue.contains(r.table()));
    }
}
