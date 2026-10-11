package io.popdesk.helpdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lines of an audit log export, for the values no request can put into the log: a name cannot
 * start with a tab or a line break (input is trimmed), and no request gives an email that starts like
 * a formula.
 */
class AuditExportLinesTest {

    private static final Instant AT = Instant.parse("2026-10-08T03:15:00.120000Z");

    private static AuditRow row(String actor, String target, String changes) {
        return new AuditRow(7, 3, "a".repeat(64), "b".repeat(64), "COMPANY_CREATED", actor, target, changes, AT);
    }

    private static String actor(String name, String email) {
        return JsonMapper.builder().build().createObjectNode().put("accountId", 12).put("name", name)
                .put("email", email).toString();
    }

    @ParameterizedTest(name = "a value that starts with code point {0}")
    @ValueSource(ints = {'=', '+', '-', '@', '\t', '\r', '\n'})
    @DisplayName("S1B-AC-25 a CSV value that starts with =, +, -, @, a tab, or a line break gets an apostrophe "
            + "in front")
    void valueThatStartsLikeAFormulaGetsAnApostrophe(int first) {
        String value = (char) first + "SUM(A1)";

        assertThat(AuditExportLines.csvCell(value)).isEqualTo("\"'" + value + "\"");
    }

    @Test
    @DisplayName("S1B-AC-25 a CSV value that starts with anything else is kept as it is, in quotes, with a quote "
            + "doubled; a formula sign later in the value changes nothing")
    void otherValuesAreOnlyQuoted() {
        assertThat(AuditExportLines.csvCell("Kim \"the boss\", a=b")).isEqualTo("\"Kim \"\"the boss\"\", a=b\"");
        assertThat(AuditExportLines.csvCell("{\"code\":\"-A\"}")).isEqualTo("\"{\"\"code\"\":\"\"-A\"\"}\"");
        assertThat(AuditExportLines.csvCell(null)).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("S1B-AC-25 the name and the email of the person are guarded like any value, and every column of "
            + "a row is in quotes")
    void personColumnsAreGuarded() {
        String line = AuditExportLines.csvLine(row(actor("\tTab Name", "=cmd@example.test"),
                "{\"type\":\"COMPANY\",\"code\":\"ACME\"}", null));

        assertThat(line).contains(",\"'\tTab Name\",\"'=cmd@example.test\",");
        assertThat(line).startsWith("\"7\",\"3\",\"" + "a".repeat(64) + "\",\"" + "b".repeat(64)
                + "\",\"COMPANY_CREATED\",\"12\",");
        assertThat(line).endsWith(",\"{\"\"type\"\":\"\"COMPANY\"\",\"\"code\"\":\"\"ACME\"\"}\",\"null\","
                + "\"2026-10-08T03:15:00.120000Z\"");
    }

    @Test
    @DisplayName("S1B-AC-23 the CSV header names the JSON Lines fields, with the person as three columns")
    void headerNamesTheColumns() {
        assertThat(AuditExportLines.csvHeader()).isEqualTo("\"id\",\"seq\",\"prevHash\",\"hash\",\"action\","
                + "\"actorAccountId\",\"actorName\",\"actorEmail\",\"target\",\"changes\",\"occurredAt\"");
        assertThat(AuditExportLines.CSV_COLUMNS).hasSize(11);
    }

    @Test
    @DisplayName("S1B-AC-24 a JSON Lines line is one JSON object on one line with the stored values as they "
            + "are, null for a target or changes the entry does not have, and the time with six fraction digits")
    void jsonLineHoldsTheStoredValues() {
        String changes = "{\"name\": \"line\\nbreak \\\"quoted\\\"\", \"n\": 5}";

        String line = AuditExportLines.jsonLine(row(actor(null, null), null, changes));

        JsonNode read = JsonMapper.builder().build().readTree(line);
        assertThat(line).doesNotContain("\n", "\r");
        assertThat(List.copyOf(read.propertyNames())).containsExactly("id", "seq", "prevHash", "hash", "action",
                "actor", "target", "changes", "occurredAt");
        assertThat(read.path("id").asLong()).isEqualTo(7);
        assertThat(read.path("seq").asLong()).isEqualTo(3);
        assertThat(read.path("target").isNull()).as("target of %s", line).isTrue();
        assertThat(read.path("actor").path("name").isNull()).as("name of %s", line).isTrue();
        assertThat(read.path("changes").path("name").asString()).isEqualTo("line\nbreak \"quoted\"");
        assertThat(read.path("occurredAt").asString()).isEqualTo("2026-10-08T03:15:00.120000Z");
    }
}
