package io.popdesk.support;

import io.popdesk.support.DbInspector.Column;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The records the app's cleanup jobs leave (OPS-2): per job, when it last ran and how many rows it
 * deleted. A slice names neither the table nor its new job, so the table is found as the one that
 * names a job of an earlier slice, and the new job's record as a row there that names none of them.
 */
public final class CleanupRecords {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DbInspector db;

    public CleanupRecords(DbInspector db) {
        this.db = db;
    }

    /**
     * A record that belongs to none of {@code earlierJobs}, ran at or after {@code since}, and counts
     * at least {@code atLeast} deleted rows; null while there is none.
     */
    public JsonNode ofNewJob(Set<String> earlierJobs, Instant since, long atLeast) {
        String table = table(earlierJobs);
        if (table == null) {
            return null;
        }
        for (Map<String, Object> r : db.rows("select row_to_json(t)::text as j from \"" + table + "\" t")) {
            JsonNode row = parse((String) r.get("j"));
            if (!names(row, earlierJobs) && ranSince(row, since) && deletedCount(row) >= atLeast) {
                return row;
            }
        }
        return null;
    }

    /** The largest number of a record in a column that is not a key (tenant_id, id, *_id, *Id), or -1. */
    public static long deletedCount(JsonNode record) {
        long max = -1;
        for (Map.Entry<String, JsonNode> column : record.properties()) {
            String name = column.getKey();
            boolean key = name.equalsIgnoreCase("id") || name.toLowerCase(Locale.ROOT).endsWith("_id")
                    || name.endsWith("Id");
            if (!key && column.getValue().isIntegralNumber()) {
                max = Math.max(max, column.getValue().asLong());
            }
        }
        return max;
    }

    /** The table with a text column equal to the name of an earlier job; null while no job left a record. */
    private String table(Set<String> earlierJobs) {
        for (String table : db.tables()) {
            for (Column c : db.columns(table)) {
                boolean textColumn = c.dataType().equals("text") || c.dataType().equals("character varying");
                if (!textColumn) {
                    continue;
                }
                for (String job : earlierJobs) {
                    if (db.count("select count(*) from \"" + table + "\" where \"" + c.name() + "\" = ?", job) > 0) {
                        return table;
                    }
                }
            }
        }
        return null;
    }

    private static boolean names(JsonNode row, Set<String> jobs) {
        for (JsonNode value : row.values()) {
            if (value.isString() && jobs.contains(value.stringValue())) {
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

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("row_to_json gave text that is not JSON: " + text, e);
        }
    }
}
