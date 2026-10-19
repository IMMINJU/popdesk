package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cleanup of reset tokens (OPS-2). The slice names neither the token table nor the job, so the
 * test looks for the tokens' hashes in any table, and for the job's record in the table that holds
 * the records of the first slice's jobs ({@code ops.CleanupRecordTest}): a row there that is none of
 * those jobs, with the time it last ran and how many rows it deleted.
 */
class PasswordResetCleanupTest extends ConnectedAppTest {

    /**
     * The cleanup jobs that are not the one of reset tokens: those of the first slice, whose rows tell
     * which table holds the records, and those later slices added, whose runs after a day must not be
     * taken for this one.
     */
    private static final Set<String> EARLIER_JOBS = Set.of("outbox-failed-cleanup", "attachment-cleanup",
            "session-cleanup", "audit-retention", "idempotency-key-cleanup", "temporary-image-cleanup",
            "trash-purge", "issue-auto-close", "sla-recalculate", "issue-retention", "company-export-expiry",
            "mail-record-cleanup");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("S1A-AC-15 after a day the cleanup has deleted used and expired reset tokens and left a record "
            + "with when it ran and how many it deleted")
    void usedAndExpiredTokensAreCleanedUp() {
        Session admin = fixtures.signInAdmin();
        String code = fixtures.createCompany(admin).code();
        String used = PasswordSteps.requestToken(portal(), mail(), fixtures.customer(admin, code).email());
        String expired = PasswordSteps.requestToken(portal(), mail(), fixtures.customer(admin, code).email());
        String alsoExpired = PasswordSteps.requestToken(portal(), mail(), fixtures.customer(admin, code).email());
        PasswordSteps.confirm(portal(), used, PasswordSteps.NEW_PASSWORD).expectStatus(204);
        assertThat(hashRows(expired)).as("rows holding the hash of an unused token").isNotEmpty();
        assertThat(hashRows(alsoExpired)).as("rows holding the hash of an unused token").isNotEmpty();

        Instant due = clock.advance(Duration.ofHours(25)).truncatedTo(ChronoUnit.MICROS);

        Wait.untilAsserted("the reset tokens are deleted", Duration.ofSeconds(90), () -> {
            assertThat(hashRows(used)).as("rows holding the hash of the used token").isEmpty();
            assertThat(hashRows(expired)).as("rows holding the hash of an expired token").isEmpty();
            assertThat(hashRows(alsoExpired)).as("rows holding the hash of an expired token").isEmpty();
        });
        JsonNode record = Wait.until("a record of a cleanup job other than " + EARLIER_JOBS + " that ran at or after "
                + due + " and deleted at least the 2 expired tokens", Duration.ofSeconds(90), () -> newJobRecord(due));
        assertThat(deletedCount(record)).as("deleted count in %s", record).isGreaterThanOrEqualTo(2);
    }

    private List<Map<String, Object>> hashRows(String token) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String form : PasswordSteps.hashForms(token)) {
            rows.addAll(AuthChecks.rowsContaining(db, form));
        }
        return rows;
    }

    /**
     * A row of the cleanup record table that belongs to none of the earlier jobs, ran at or after
     * {@code since}, and counts at least 2 deleted rows; null while there is none.
     */
    private JsonNode newJobRecord(Instant since) {
        for (String table : db.tables()) {
            List<JsonNode> rows = db.rows("select row_to_json(t)::text as j from \"" + table + "\" t").stream()
                    .map(r -> parse((String) r.get("j"))).toList();
            if (rows.stream().noneMatch(row -> hasTextIn(row, EARLIER_JOBS))) {
                continue;
            }
            for (JsonNode row : rows) {
                if (!hasTextIn(row, EARLIER_JOBS) && ranSince(row, since) && deletedCount(row) >= 2) {
                    return row;
                }
            }
        }
        return null;
    }

    private static boolean hasTextIn(JsonNode row, Set<String> texts) {
        for (JsonNode value : row.values()) {
            if (value.isString() && texts.contains(value.stringValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean ranSince(JsonNode row, Instant since) {
        for (JsonNode value : row.values()) {
            if (value.isString()) {
                try {
                    Instant time = OffsetDateTime.parse(value.stringValue()).toInstant().truncatedTo(ChronoUnit.MICROS);
                    if (!time.isBefore(since)) {
                        return true;
                    }
                } catch (DateTimeParseException notATime) {
                    // Another text column of the row.
                }
            }
        }
        return false;
    }

    /** The largest number in a column that is not a key (tenant_id, id, *_id, *Id), or -1. */
    private static long deletedCount(JsonNode row) {
        long max = -1;
        for (Map.Entry<String, JsonNode> column : row.properties()) {
            String name = column.getKey();
            boolean key = name.equalsIgnoreCase("id") || name.toLowerCase(Locale.ROOT).endsWith("_id")
                    || name.endsWith("Id");
            if (!key && column.getValue().isIntegralNumber()) {
                max = Math.max(max, column.getValue().asLong());
            }
        }
        return max;
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("row_to_json gave text that is not JSON: " + text, e);
        }
    }
}
