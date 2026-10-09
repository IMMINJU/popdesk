package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The record of cleanup jobs (OPS-2): one row per job, rewritten each time it runs, with the time it
 * last ran and how many rows it deleted. The jobs are {@value #OUTBOX}, {@value #ATTACHMENTS}, and
 * {@value #SESSIONS} (tasks.md section 6). The table and its columns are not named by the slice; the
 * row of a job is the business-table row whose text value is the job's name.
 */
class CleanupRecordTest extends BareAppTest {

    static final String OUTBOX = "outbox-failed-cleanup";
    static final String ATTACHMENTS = "attachment-cleanup";
    static final String SESSIONS = "session-cleanup";
    static final List<String> JOBS = List.of(OUTBOX, ATTACHMENTS, SESSIONS);

    /** The one row per job, or null while some job has no row or more than one, or ran before {@code since}. */
    private Map<String, Row> rowsRunSince(Instant since) {
        AuditTrail tables = new AuditTrail(db);
        Map<String, Row> out = new LinkedHashMap<>();
        for (String job : JOBS) {
            List<Row> rows = tables.rowsContaining(job).stream().filter(r -> r.hasText(job)).toList();
            if (rows.size() != 1 || rows.getFirst().times().stream().noneMatch(t -> !t.isBefore(since))) {
                return null;
            }
            out.put(job, rows.getFirst());
        }
        return out;
    }

    /** The largest number in a column that is not a key (tenant_id, id, *_id, *Id), or -1. */
    private static long count(Row row) {
        long max = -1;
        for (Map.Entry<String, JsonNode> e : row.json().properties()) {
            String name = e.getKey();
            boolean key = name.equalsIgnoreCase("id") || name.toLowerCase(Locale.ROOT).endsWith("_id")
                    || name.endsWith("Id");
            if (!key && e.getValue().isIntegralNumber()) {
                max = Math.max(max, e.getValue().asLong());
            }
        }
        return max;
    }

    @Test
    @DisplayName("S0-AC-82 after a day each cleanup job has one row with when it ran and how many it deleted")
    void jobsRecordRunAndCount() {
        Session admin = fixtures.signInAdmin();
        fixtures.agent(admin);
        Instant due = clock.advance(Duration.ofHours(25)).truncatedTo(ChronoUnit.MICROS);

        Map<String, Row> rows = Wait.until("a row per cleanup job run at or after " + due, Duration.ofSeconds(90),
                () -> rowsRunSince(due));

        assertThat(rows.values().stream().map(Row::table).distinct()).as("tables of the job rows").hasSize(1);
        for (Row row : rows.values()) {
            assertThat(count(row)).as("deleted count in %s", row).isGreaterThanOrEqualTo(0);
        }
        assertThat(count(rows.get(SESSIONS))).as("ended sessions of the admin and the agent, in %s",
                rows.get(SESSIONS)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("S0-AC-82 a job that runs again rewrites its row instead of adding one")
    void rowRewritten() {
        fixtures.signInAdmin();
        Instant first = clock.advance(Duration.ofHours(25)).truncatedTo(ChronoUnit.MICROS);
        Wait.until("a row per cleanup job run at or after " + first, Duration.ofSeconds(90), () -> rowsRunSince(first));

        Instant second = clock.advance(Duration.ofHours(25)).truncatedTo(ChronoUnit.MICROS);
        Map<String, Row> rows = Wait.until("a row per cleanup job run at or after " + second, Duration.ofSeconds(90),
                () -> rowsRunSince(second));

        assertThat(rows).as("one row per job").hasSize(3);
    }
}
