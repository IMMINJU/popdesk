package io.popdesk.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.attachment.AttachmentSteps.Scene;
import io.popdesk.attachment.StuckAttachments.Stuck;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Wait;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The number the attachment cleanup records for a run (OPS-2): how many attachments it ended.
 * {@code ops.CleanupRecordTest} checks that every job has a row with a time and a count; here the run
 * has attachments to end, so the count is not zero.
 *
 * <p>The record's table and columns are not named by the slice. As in {@link AttachmentCleanupTest},
 * the row is the one with a text value {@code attachment-cleanup}; its count is the largest whole
 * number in a column that is not a key. Other tests of this context can leave rows for the same run,
 * so the count is at least what this test left.
 */
class CleanupCountTest extends BareAppTest {

    private static final String JOB = "attachment-cleanup";

    private static final Duration PAST_A_DAY = Duration.ofHours(25);

    @Test
    @DisplayName("S0-AC-82 a cleanup run that ends two pending attachments records a count of at least two")
    void countsEndedAttachments() {
        Scene s = new AttachmentSteps(fixtures).scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        StuckAttachments stuck = new StuckAttachments(db);
        Stuck first = stuck.pending(s.customer(), issue.key());
        Stuck second = stuck.pending(s.customer(), issue.key());
        AttachmentRows rows = new AttachmentRows(db);

        Instant due = clock.advance(PAST_A_DAY).truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> record = Wait.until("the record of " + JOB + " for a run at or after " + due,
                Duration.ofSeconds(60), () -> recordSince(due));

        assertThat(rows.contentCopies(first.content()) + rows.contentCopies(second.content()))
                .as("content rows of the two pending attachments after the run").isZero();
        assertThat(count(record)).as("ended attachments in %s", record).isGreaterThanOrEqualTo(2);
    }

    /** The job's row when it holds a time at or after {@code since}, else null. */
    private Map<String, Object> recordSince(Instant since) {
        for (String table : db.tables()) {
            for (DbInspector.Column c : db.columns(table)) {
                if (!c.dataType().equals("text") && !c.dataType().equals("character varying")) {
                    continue;
                }
                for (Map<String, Object> row : db.rows("select * from " + AttachmentRows.quote(table) + " where "
                        + AttachmentRows.quote(c.name()) + " = '" + JOB + "'")) {
                    for (Object value : row.values()) {
                        Instant time = value instanceof Timestamp ts ? ts.toInstant()
                                : value instanceof OffsetDateTime odt ? odt.toInstant() : null;
                        if (time != null && !time.isBefore(since)) {
                            return row;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The largest whole number in a column that is not a key (id, *_id), or -1. */
    private static long count(Map<String, Object> row) {
        long max = -1;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            boolean key = name.equals("id") || name.endsWith("_id");
            if (!key && (e.getValue() instanceof Long || e.getValue() instanceof Integer)) {
                max = Math.max(max, ((Number) e.getValue()).longValue());
            }
        }
        return max;
    }
}
