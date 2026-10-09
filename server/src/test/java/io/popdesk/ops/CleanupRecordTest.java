package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The record of cleanup jobs (OPS-2): for each job of this slice (failed outbox rows, pending and
 * purging attachments, ended sessions) the time it last ran and how many rows it deleted.
 *
 * <p>The table is not named by the slice. It is found as the business table that, after the clock
 * moves a day on, has at least three rows with a time at or after that moment and no person in
 * them (audit rows have one). The test signs nobody in after moving the clock, so no audit row
 * gets that time.
 */
class CleanupRecordTest extends BareAppTest {

    /** Rows per table that carry a time at or after {@code since} and no {accountId, name, email} object. */
    private Map<String, List<Row>> rowsRunSince(Instant since) {
        Map<String, List<Row>> out = new LinkedHashMap<>();
        for (Row row : new AuditTrail(db).rowsContaining()) {
            if (row.people().isEmpty() && row.times().stream().anyMatch(t -> !t.isBefore(since))) {
                out.computeIfAbsent(row.table(), k -> new ArrayList<>()).add(row);
            }
        }
        return out;
    }

    /** Whether a number column that is not a key (tenant_id, id, *_id, *Id) holds at least {@code atLeast}. */
    private static boolean hasCount(Row row, long atLeast) {
        for (Map.Entry<String, JsonNode> e : row.json().properties()) {
            String name = e.getKey();
            boolean key = name.equalsIgnoreCase("id") || name.toLowerCase(Locale.ROOT).endsWith("_id")
                    || name.endsWith("Id");
            if (!key && e.getValue().isIntegralNumber() && e.getValue().asLong() >= atLeast) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("S0-AC-82 after a day each cleanup job has a row with when it ran and how many it deleted")
    void jobsRecordRunAndCount() {
        Session admin = fixtures.signInAdmin();
        fixtures.agent(admin);
        Instant due = clock.advance(Duration.ofHours(25)).truncatedTo(ChronoUnit.MICROS);

        Map<String, List<Row>> tables = Wait.until("cleanup rows with a run time at or after " + due,
                Duration.ofSeconds(90), () -> {
                    Map<String, List<Row>> found = rowsRunSince(due);
                    found.values().removeIf(rows -> rows.size() < 3);
                    return found.size() == 1 ? found : null;
                });

        List<Row> rows = tables.values().iterator().next();
        assertThat(rows).as("cleanup rows in %s", tables.keySet()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rows).as("rows with a deleted count (the agent's and admin's sessions ended)")
                .anyMatch(r -> hasCount(r, 1));
        assertThat(rows).as("every row has a count").allMatch(r -> hasCount(r, 0));
    }
}
