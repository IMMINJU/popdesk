package io.popdesk.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps of the audit log tests of slice 1b, through the admin API only: reading the log with
 * filters, exporting it, and computing the hash chain again from what the API gave.
 *
 * <p>The audit table is shared by every test of a context, so a test reads its own entries with a
 * filter (an actor or a target it made itself): {@code entries(admin, "actorAccountId", id, "action",
 * "COMPANY_CREATED")}. Query values come in pairs, name then value; a name may repeat.
 *
 * <p>The chain is computed without any class of the app, by {@code support.AuditChainMath}; the
 * methods here pass on to it. {@link #hash} is
 * {@code SHA-256(prevHash as 64 lowercase hex characters ‖ canonical JSON)}, and {@link #chainJson} is
 * the RFC 8785 form of {@code {action, actor{accountId, email, name}, changes, occurredAt, target}}
 * with null values kept and the time in UTC with six fraction digits. Audit values hold no
 * fractions, so a number that is not an integer fails here.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
final class AuditSteps {

    static final String LOG = "/api/console/audit-log";

    static final String EXPORT = LOG + "/export";

    /** The {@code prevHash} of the first entry of a chain. */
    static final String ZERO_HASH = "0".repeat(64);

    static final String HASH_PATTERN = "^[0-9a-f]{64}$";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int MAX_PAGES = 1000;

    private AuditSteps() {}

    // Reading

    /** Adds query parameters given as name, value, name, value, ... */
    static ApiRequest with(ApiRequest request, Object... query) {
        if (query.length % 2 != 0) {
            throw new IllegalArgumentException("Query values come in pairs: name, value");
        }
        for (int i = 0; i < query.length; i += 2) {
            request.query((String) query[i], query[i + 1]);
        }
        return request;
    }

    /** {@code GET /api/console/audit-log} with these query parameters, not sent yet. */
    static ApiRequest log(Session admin, Object... query) {
        return with(admin.client().get(LOG), query);
    }

    /** Every page of the log narrowed by {@code query}, 100 entries a page; each must answer 200. */
    static List<ApiResponse> pages(Session admin, Object... query) {
        List<ApiResponse> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ApiResponse response = log(admin, query).query("page", page).query("size", 100).expect(200);
            if (!response.json().path("items").isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            out.add(response);
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("The audit log narrowed by this query has more than " + MAX_PAGES + " pages");
    }

    /** Every entry the query finds, in the order the log gives them (newest first). */
    static List<JsonNode> entries(Session admin, Object... query) {
        List<JsonNode> out = new ArrayList<>();
        for (ApiResponse page : pages(admin, query)) {
            page.json().path("items").forEach(out::add);
        }
        return out;
    }

    /** The one entry the query finds. */
    static JsonNode onlyEntry(Session admin, Object... query) {
        List<JsonNode> found = entries(admin, query);
        assertThat(found).as("audit entries found by %s", List.of(query)).hasSize(1);
        return found.getFirst();
    }

    /** A text field of an entry, or of an object inside it. Fails when it is missing or not text. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new AssertionError("No text field '" + field + "' in " + node);
        }
        return value.stringValue();
    }

    /** A whole-number field. Fails when it is missing or not a whole number. */
    static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError("No whole-number field '" + field + "' in " + node);
        }
        return value.asLong();
    }

    /** {@code occurredAt} of an entry as an instant. */
    static Instant time(JsonNode entry) {
        return instant(text(entry, "occurredAt"));
    }

    /** A time the API wrote, as an instant. */
    static Instant instant(String written) {
        try {
            return OffsetDateTime.parse(written).toInstant();
        } catch (DateTimeParseException e) {
            throw new AssertionError("Not a time: '" + written + "'", e);
        }
    }

    // Exporting

    /** {@code GET /api/console/audit-log/export}; {@code format} is {@code CSV} or {@code JSONL}. Sent, any status. */
    static ApiResponse export(Session admin, String format, Object... query) {
        return with(admin.client().get(EXPORT).query("format", format), query).send();
    }

    /** {@code HEAD /api/console/audit-log/export}. Sent, any status. */
    static ApiResponse exportHead(Session admin, String format, Object... query) {
        return with(admin.client().head(EXPORT).query("format", format), query).send();
    }

    /** The lines of a JSON Lines body, each as JSON. An empty body has no lines. */
    static List<JsonNode> jsonLines(ApiResponse response) {
        List<JsonNode> out = new ArrayList<>();
        for (String line : response.text().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(JSON.readTree(line));
            } catch (JacksonException e) {
                throw new AssertionError(response.request() + ": a line that is not JSON: " + line, e);
            }
        }
        return out;
    }

    /**
     * The rows of a CSV text (RFC 4180): commas, values in double quotes with {@code ""} for a quote,
     * line breaks inside quotes kept, CRLF or LF between rows, a byte order mark at the start dropped.
     * The first row is the header.
     */
    static List<List<String>> csv(String text) {
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    quoted = true;
                    any = true;
                }
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    any = true;
                }
                case '\r', '\n' -> {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        i++;
                    }
                    if (any || !cell.isEmpty()) {
                        row.add(cell.toString());
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                    cell.setLength(0);
                    any = false;
                }
                default -> cell.append(c);
            }
        }
        if (quoted) {
            throw new AssertionError("The CSV ends inside a quoted value");
        }
        if (any || !cell.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // The hash chain

    /** A value as JSON, read for comparing with {@link #canonical}. */
    static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    /** JSON text as JSON. */
    static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("Not JSON: " + text, e);
        }
    }

    /**
     * The RFC 8785 text of a JSON value: keys sorted by UTF-16 code units, no whitespace, strings
     * escaped as {@code JSON.stringify} does. Two values are the same JSON when this text is the same,
     * whatever the order of their keys. A number with a fraction fails.
     */
    static String canonical(JsonNode value) {
        return AuditChainMath.canonical(value);
    }

    /** The text the chain hashes for an entry (a log entry or an export line). */
    static String chainJson(JsonNode entry) {
        return AuditChainMath.chainJson(entry);
    }

    /** {@code SHA-256(prevHash ‖ chainJson(entry))} as 64 lowercase hex characters. */
    static String hash(String prevHash, JsonNode entry) {
        return AuditChainMath.hash(prevHash, entry);
    }

    /**
     * Checks entries given in chain order (an export without a filter, or any run of entries with
     * no gap): each {@code hash} is the hash computed again, each {@code prevHash} is the {@code hash}
     * of the entry before, {@code seq} goes up by one, and the entry with {@code seq} 1 starts from
     * {@link #ZERO_HASH}.
     */
    static void assertChain(List<JsonNode> inChainOrder) {
        assertThat(inChainOrder).as("entries of the chain").isNotEmpty();
        JsonNode before = null;
        for (JsonNode entry : inChainOrder) {
            long seq = number(entry, "seq");
            String prevHash = text(entry, "prevHash");
            assertThat(text(entry, "hash")).as("hash of seq %d computed again from %s", seq, chainJson(entry))
                    .isEqualTo(hash(prevHash, entry));
            if (before != null) {
                assertThat(seq).as("seq after %d", number(before, "seq")).isEqualTo(number(before, "seq") + 1);
                assertThat(prevHash).as("prevHash of seq %d", seq).isEqualTo(text(before, "hash"));
            }
            if (seq == 1) {
                assertThat(prevHash).as("prevHash of the first entry").isEqualTo(ZERO_HASH);
            }
            before = entry;
        }
    }
}
