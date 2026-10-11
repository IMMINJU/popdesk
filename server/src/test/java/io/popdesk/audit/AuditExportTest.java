package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.assertChain;
import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.csv;
import static io.popdesk.audit.AuditSteps.entries;
import static io.popdesk.audit.AuditSteps.export;
import static io.popdesk.audit.AuditSteps.exportHead;
import static io.popdesk.audit.AuditSteps.instant;
import static io.popdesk.audit.AuditSteps.json;
import static io.popdesk.audit.AuditSteps.jsonLines;
import static io.popdesk.audit.AuditSteps.number;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.AuditSteps.parse;
import static io.popdesk.audit.AuditSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Exporting the audit log as CSV and as JSON Lines: what is in the file, that the chain can be
 * computed again from it, values a spreadsheet would read as a formula, and the entry the export
 * itself leaves.
 *
 * <p>The CSV is read by its header row. The column names this test relies on are the JSON Lines
 * names {@code id}, {@code seq}, {@code prevHash}, {@code hash}, {@code action}, {@code target},
 * {@code changes}, {@code occurredAt}; the names of the three actor columns are not fixed here.
 */
class AuditExportTest extends BareAppTest {

    private static final int CSV_COLUMNS = 11;

    /** The file formats, with what the response says about each. */
    enum Format {
        CSV("text/csv;charset=utf-8", ".csv"),
        JSONL("application/x-ndjson", ".jsonl");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    /** One entry as both the log and a file give it; JSON values in canonical text. */
    private record Line(long id, long seq, String prevHash, String hash, String action, String target,
            String changes, Instant occurredAt) {

        static Line of(JsonNode entry) {
            return new Line(number(entry, "id"), number(entry, "seq"), text(entry, "prevHash"), text(entry, "hash"),
                    text(entry, "action"), canonical(entry.path("target")), canonical(entry.path("changes")),
                    AuditSteps.time(entry));
        }
    }

    private Session newAdmin() {
        return fixtures.admin(fixtures.signInAdmin());
    }

    private static long whole(String cell, String column) {
        try {
            return Long.parseLong(cell);
        } catch (NumberFormatException e) {
            throw new AssertionError("CSV column " + column + " holds '" + cell + "', not a whole number", e);
        }
    }

    private static String cell(List<String> header, List<String> row, String column) {
        int at = header.indexOf(column);
        if (at < 0) {
            throw new AssertionError("No CSV column '" + column + "' in the header " + header);
        }
        if (at >= row.size()) {
            throw new AssertionError("A CSV row with " + row.size() + " values, fewer than the header: " + row);
        }
        return row.get(at);
    }

    /** The data rows of a CSV export, read by the header's column names. */
    private static List<Line> csvLines(ApiResponse file) {
        List<List<String>> rows = csv(file.text());
        assertThat(rows).as("rows of the CSV, header included").isNotEmpty();
        List<String> header = rows.getFirst();
        assertThat(header).as("CSV header").hasSize(CSV_COLUMNS).doesNotHaveDuplicates();
        return rows.stream().skip(1).map(row -> {
            assertThat(row).as("a CSV row under the header %s", header).hasSameSizeAs(header);
            return new Line(whole(cell(header, row, "id"), "id"), whole(cell(header, row, "seq"), "seq"),
                    cell(header, row, "prevHash"), cell(header, row, "hash"), cell(header, row, "action"),
                    canonical(parse(cell(header, row, "target"))), canonical(parse(cell(header, row, "changes"))),
                    instant(cell(header, row, "occurredAt")));
        }).toList();
    }

    private static List<Line> lines(Format format, ApiResponse file) {
        return format == Format.CSV ? csvLines(file) : jsonLines(file).stream().map(Line::of).toList();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Format.class)
    @DisplayName("S1B-AC-23 an export has every entry the same filter finds in the log, in chain order (seq, "
            + "lowest first), each with its ID, seq, hash, and previous hash; the response is a file that is not "
            + "stored")
    void exportHasEveryEntryOfTheFilterInChainOrder(Format format) {
        Session admin = newAdmin();
        Company first = fixtures.createCompany(admin);
        fixtures.createCompany(admin);
        admin.client().put("/api/console/companies/" + first.code() + "/status")
                .json(Map.of("status", "SUSPENDED")).expect(200);
        fixtures.createCompany(admin);
        Object[] filter = {"actorAccountId", admin.accountId(), "action", "COMPANY_CREATED", "action",
                "COMPANY_STATUS_CHANGED"};
        List<Line> inLog = entries(admin, filter).stream().map(Line::of).toList();

        ApiResponse file = export(admin, format.name(), filter).expectStatus(200);

        assertThat(inLog).as("entries the filter finds in the log").hasSize(4);
        assertThat(lines(format, file)).as("lines of the %s file", format)
                .containsExactlyElementsOf(inLog.stream().sorted(Comparator.comparingLong(Line::seq)).toList());
        assertThat(file.header("Content-Type")).as("Content-Type").isNotNull();
        assertThat(file.header("Content-Type").replace(" ", "").toLowerCase(Locale.ROOT)).as("Content-Type")
                .isEqualTo(format.contentType);
        assertThat(file.header("Content-Disposition")).as("Content-Disposition").isNotNull()
                .startsWith("attachment").containsPattern("filename[^;]*" + format.extension.replace(".", "\\.")
                        + "\"?\\s*(;|$)");
        assertThat(file.header("Cache-Control")).as("Cache-Control").isNotNull().contains("no-store");
    }

    @Test
    @DisplayName("S1B-AC-24 a JSON Lines export without a filter is enough to compute the chain again: each "
            + "line's hash is SHA-256 of its previous hash and its canonical JSON, and each previous hash is the "
            + "hash of the line before")
    void chainComputesAgainFromAnUnfilteredJsonLinesFile() {
        Session admin = newAdmin();
        String name = "회사 \"quoted\" \\ back/slash 😀 " + Unique.number();
        Company company = fixtures.createCompany(admin, Unique.companyCode(), name, name + " (주)");

        ApiResponse file = export(admin, "JSONL").expectStatus(200);

        List<JsonNode> lines = jsonLines(file);
        assertThat(lines).as("lines of the file").allSatisfy(line -> assertThat(line.propertyNames())
                .as("fields of %s", line).containsExactlyInAnyOrder("id", "seq", "prevHash", "hash", "action",
                        "actor", "target", "changes", "occurredAt"));
        assertThat(lines.stream().filter(line -> company.code().equals(line.path("target").path("code")
                .asString(null))).map(line -> line.path("changes").path("name").asString(null)).toList())
                .as("the line of the company this test made").containsExactly(name);
        assertChain(lines);
    }

    @ParameterizedTest(name = "a name that starts with {0}")
    @ValueSource(strings = {"=1+1", "+a", "-a", "@a"})
    @DisplayName("S1B-AC-25 a CSV value that starts with =, +, -, or @ gets an apostrophe in front")
    void csvValueThatStartsLikeAFormulaGetsAnApostrophe(String start) {
        Session admin = newAdmin();
        Session renamed = fixtures.admin(admin);
        String name = start + " " + Unique.number();
        admin.client().patch("/api/console/accounts/" + renamed.accountId()).json(Map.of("name", name)).expect(200);
        fixtures.createCompany(fixtures.signInAgain(renamed));
        Object[] filter = {"actorAccountId", renamed.accountId(), "action", "COMPANY_CREATED"};

        ApiResponse file = export(admin, "CSV", filter).expectStatus(200);

        List<List<String>> rows = csv(file.text());
        assertThat(text(onlyEntry(admin, filter).path("actor"), "name")).as("the name in the log, as stored")
                .isEqualTo(name);
        assertThat(rows).as("rows of the CSV: the header and the one entry").hasSize(2);
        assertThat(rows.get(1)).as("values of the entry's row").contains("'" + name).doesNotContain(name);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Format.class)
    @DisplayName("S1B-AC-26 an export leaves an AUDIT_EXPORTED entry: who exported, the target AUDIT_LOG, and "
            + "the format, the number of entries, and the filter")
    void exportIsRecorded(Format format) {
        Session admin = newAdmin();
        Company company = fixtures.createCompany(admin);

        ApiResponse file = export(admin, format.name(), "targetCode", company.code(), "action", "COMPANY_CREATED")
                .expectStatus(200);

        JsonNode entry = onlyEntry(admin, "actorAccountId", admin.accountId(), "action", "AUDIT_EXPORTED");
        JsonNode changes = entry.path("changes");
        assertThat(lines(format, file)).as("lines of the file").hasSize(1);
        assertThat(canonical(entry.path("actor"))).as("who exported").isEqualTo(canonical(json(Map.of(
                "accountId", admin.accountId(), "name", admin.name(), "email", admin.email()))));
        assertThat(canonical(entry.path("target"))).as("target").isEqualTo("{\"type\":\"AUDIT_LOG\"}");
        assertThat(changes.propertyNames()).as("changes of %s", entry).containsExactlyInAnyOrder("format", "count",
                "filter");
        assertThat(text(changes, "format")).isEqualTo(format.name());
        assertThat(number(changes, "count")).as("count").isEqualTo(1);
        assertThat(changes.path("filter").propertyNames()).as("filter of %s", entry)
                .containsExactlyInAnyOrder("targetCode", "action");
        assertThat(text(changes.path("filter"), "targetCode")).isEqualTo(company.code());
        assertThat(changes.path("filter").path("action").toString()).as("the kinds in the filter")
                .contains("COMPANY_CREATED");
    }

    @Test
    @DisplayName("S1B-AC-26 the entry of an export is not in its own file; without a filter its filter is empty "
            + "and its count is the number of lines")
    void exportEntryIsNotInItsOwnFile() {
        Session admin = newAdmin();

        ApiResponse file = export(admin, "JSONL").expectStatus(200);

        List<JsonNode> lines = jsonLines(file);
        JsonNode entry = onlyEntry(admin, "actorAccountId", admin.accountId(), "action", "AUDIT_EXPORTED");
        assertThat(lines).as("lines of the file").isNotEmpty();
        assertThat(lines.stream().map(line -> number(line, "id")).toList()).as("IDs in the file")
                .doesNotContain(number(entry, "id"));
        assertThat(number(entry, "seq")).as("seq of the export's entry")
                .isGreaterThan(number(lines.getLast(), "seq"));
        assertThat(canonical(entry.path("changes").path("filter"))).as("filter").isEqualTo("{}");
        assertThat(number(entry.path("changes"), "count")).as("count").isEqualTo(lines.size());
    }

    @Test
    @DisplayName("S1B-AC-26 HEAD on the export answers with the file's headers and no body, and leaves no entry")
    void headLeavesNoEntry() {
        Session admin = newAdmin();
        fixtures.createCompany(admin);

        ApiResponse head = exportHead(admin, "CSV", "actorAccountId", admin.accountId());

        assertThat(head.status()).as(head.request()).isEqualTo(200);
        assertThat(head.body()).as("body of the HEAD").isEmpty();
        assertThat(head.header("Content-Disposition")).as("Content-Disposition").isNotNull()
                .startsWith("attachment").contains(".csv");
        assertThat(head.header("Content-Type")).as("Content-Type").isNotNull().startsWith("text/csv");
        assertThat(entries(admin, "actorAccountId", admin.accountId(), "action", "AUDIT_EXPORTED"))
                .as("AUDIT_EXPORTED entries after the HEAD").isEmpty();
    }
}
