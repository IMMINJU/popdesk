package io.popdesk.helpdesk.audit;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lines of an audit log export (S1B-AC-23 to S1B-AC-25, docs/integrate/conventions.md "Audit log
 * export"). Both formats give the stored values and the stored hashes; nothing is computed again.
 */
final class AuditExportLines {

    /** The CSV columns: the JSON Lines names, with the person as three columns. */
    static final List<String> CSV_COLUMNS = List.of("id", "seq", "prevHash", "hash", "action", "actorAccountId",
            "actorName", "actorEmail", "target", "changes", "occurredAt");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuditExportLines() {}

    /**
     * One JSON object without a line break: {@code id}, {@code seq}, {@code prevHash}, {@code hash},
     * {@code action}, {@code actor}, {@code target}, {@code changes}, {@code occurredAt}. The three
     * JSON values are the stored text as it is.
     */
    static String jsonLine(AuditRow row) {
        return "{\"id\":" + row.id()
                + ",\"seq\":" + row.seq()
                + ",\"prevHash\":" + JSON.writeValueAsString(row.prevHash())
                + ",\"hash\":" + JSON.writeValueAsString(row.hash())
                + ",\"action\":" + JSON.writeValueAsString(row.action())
                + ",\"actor\":" + row.actor()
                + ",\"target\":" + jsonOrNull(row.target())
                + ",\"changes\":" + jsonOrNull(row.changes())
                + ",\"occurredAt\":\"" + CanonicalAuditJson.time(row.occurredAt()) + "\"}";
    }

    static String csvHeader() {
        return String.join(",", CSV_COLUMNS.stream().map(AuditExportLines::csvCell).toList());
    }

    /** One CSV row without the line break, in the order of {@link #CSV_COLUMNS}. */
    static String csvLine(AuditRow row) {
        JsonNode actor = actor(row.actor());
        return String.join(",", List.of(
                csvCell(Long.toString(row.id())),
                csvCell(Long.toString(row.seq())),
                csvCell(row.prevHash()),
                csvCell(row.hash()),
                csvCell(row.action()),
                csvCell(plain(actor.path("accountId"))),
                csvCell(plain(actor.path("name"))),
                csvCell(plain(actor.path("email"))),
                csvCell(jsonOrNull(row.target())),
                csvCell(jsonOrNull(row.changes())),
                csvCell(CanonicalAuditJson.time(row.occurredAt()))));
    }

    /**
     * A CSV value: always in double quotes, a quote doubled. A value that starts with {@code =},
     * {@code +}, {@code -}, {@code @}, a tab, or a line break gets an apostrophe in front, so a
     * spreadsheet shows it as text instead of running it as a formula (S1B-AC-25).
     */
    static String csvCell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String jsonOrNull(String json) {
        return json == null ? "null" : json;
    }

    /** The person of a row; an empty object when a changed row holds something else. */
    private static JsonNode actor(String json) {
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            return JSON.createObjectNode();
        }
    }

    /** Text as it is, nothing for null, any other value as its JSON. */
    private static String plain(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.isString() ? value.stringValue() : value.toString();
    }
}
