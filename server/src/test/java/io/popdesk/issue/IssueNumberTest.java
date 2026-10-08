package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.keys;
import static io.popdesk.issue.IssueSteps.numberOf;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.issue.IssueTables.Constraint;
import io.popdesk.issue.IssueTables.Found;
import io.popdesk.issue.IssueTables.UniqueIndex;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue numbers: per company from 1 without gaps, not used by failed filings, and the schema that
 * keeps them apart. Concurrent filing is in {@link IssueConcurrencyTest}.
 */
class IssueNumberTest extends BareAppTest {

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    @Test
    @DisplayName("S0-AC-44 numbers start at 1 in each company and go up by one")
    void numbersStartAtOnePerCompany() {
        Scene a = steps.scene();
        Company b = fixtures.createCompany(a.admin());
        Session ofB = fixtures.customer(a.admin(), b.code());

        Issue a1 = fixtures.fileIssue(a.customer(), a.code());
        Issue b1 = fixtures.fileIssue(ofB, b.code());
        Issue a2 = fixtures.fileIssue(a.customer(), a.code());
        Issue a3 = fixtures.fileIssue(a.customer(), a.code());

        assertThat(List.of(a1.key(), a2.key(), a3.key()))
                .containsExactly(a.code() + "-1", a.code() + "-2", a.code() + "-3");
        assertThat(b1.key()).isEqualTo(b.code() + "-1");
    }

    @Test
    @DisplayName("S0-AC-44 a filing that fails with 400 does not use a number")
    void failedFilingUsesNoNumber() {
        Scene s = steps.scene();
        Issue first = fixtures.fileIssue(s.customer(), s.code());

        Map<String, Object> badPriority = new LinkedHashMap<>();
        badPriority.put("companyCode", s.code());
        badPriority.put("title", Unique.text("Bad priority"));
        badPriority.put("priority", "CRITICAL");
        ApiResponse invalid = s.customer().client().post("/api/portal/issues").json(badPriority).send();

        Map<String, Object> withEmptyFile = new LinkedHashMap<>();
        withEmptyFile.put("companyCode", s.code());
        withEmptyFile.put("title", Unique.text("Empty file"));
        ApiResponse emptyFile = s.customer().client().post("/api/portal/issues")
                .jsonPart("issue", withEmptyFile)
                .file("files", "empty.txt", "text/plain", new byte[0])
                .send();

        Issue next = fixtures.fileIssue(s.customer(), s.code());

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(emptyFile.status()).isEqualTo(400);
        assertThat(emptyFile.errorCode()).isEqualTo("FILE_EMPTY");
        assertThat(first.key()).isEqualTo(s.code() + "-1");
        assertThat(next.key()).isEqualTo(s.code() + "-2");
        assertThat(keys(s.customer().client().get("/api/portal/issues").expect(200)))
                .containsExactlyInAnyOrder(first.key(), next.key());
    }

    // Schema. The tables are found by the data filed here (IssueTables).

    @Test
    @DisplayName("S0-AC-44 the company's number counter column has no unique index")
    void counterColumnHasNoUniqueIndex() {
        Layout layout = layout();

        assertThat(layout.counterColumns()).as("company columns that go up by one per issue").isNotEmpty();
        List<UniqueIndex> unique = tables().uniqueIndexes(layout.found().company().table());
        for (String counter : layout.counterColumns()) {
            assertThat(unique)
                    .as("unique indexes on %s covering %s", layout.found().company().table(), counter)
                    .noneMatch(i -> i.columns().contains(counter) || i.definition().contains(counter));
        }
    }

    @Test
    @DisplayName("S0-AC-44 the issue table has a unique constraint on (company, number)")
    void issueTableHasUniqueCompanyAndNumber() {
        Layout layout = layout();

        List<Constraint> constraints = tables().constraints(layout.found().issue().table());

        assertThat(constraints)
                .filteredOn(c -> c.type().equals("u"))
                .extracting(Constraint::columns)
                .contains(IssueTables.union(layout.found().companyKey(), layout.numberColumn()));
    }

    @Test
    @DisplayName("S0-AC-44 the issue number column is NOT NULL")
    void issueNumberIsNotNull() {
        Layout layout = layout();

        assertThat(tables().notNull(layout.found().issue().table(), layout.numberColumn())).isTrue();
    }

    @Test
    @DisplayName("S0-AC-44 the attachment's foreign key to the issue is DEFERRABLE INITIALLY DEFERRED")
    void attachmentKeyToIssueIsDeferred() {
        Layout layout = layout();

        Constraint key = tables().attachmentKeyToIssue(layout.fileName(), layout.found().issue().table());

        assertThat(key.deferrable()).as("%s deferrable", key.name()).isTrue();
        assertThat(key.initiallyDeferred()).as("%s initially deferred", key.name()).isTrue();
    }

    private IssueTables tables() {
        return new IssueTables(db);
    }

    /** What the schema tests look at, found from three filed issues (the first with a file). */
    private record Layout(Found found, String numberColumn, List<String> counterColumns, String fileName) {}

    private Layout layout() {
        Scene s = steps.scene();
        String fileName = "layout-" + Unique.number() + ".txt";
        IssueTables tables = tables();

        List<Issue> issues = new ArrayList<>();
        List<Map<String, Object>> companyRows = new ArrayList<>();
        Found found = null;
        for (int i = 0; i < 3; i++) {
            NewIssue next = NewIssue.in(s.code());
            if (i == 0) {
                next.file(File.text(fileName, "content of " + fileName));
            }
            Issue issue = fixtures.fileIssue(s.customer(), next);
            issues.add(issue);
            if (found == null) {
                found = tables.find(s.company().legalName(), text(issue.json(), "title"));
            }
            companyRows.add(tables.row(found.company(), s.company().legalName()));
        }

        List<String> numberColumns = new ArrayList<>();
        for (String column : tables.integerColumns(found.issue().table())) {
            boolean matches = true;
            for (Issue issue : issues) {
                Object value = tables.row(found.issue(), text(issue.json(), "title")).get(column);
                matches &= value instanceof Number n && n.longValue() == numberOf(issue.key());
            }
            if (matches) {
                numberColumns.add(column);
            }
        }
        assertThat(numberColumns).as("issue columns holding the number of the key").hasSize(1);

        List<String> counters = new ArrayList<>();
        for (String column : tables.integerColumns(found.company().table())) {
            boolean upByOne = true;
            for (int i = 1; i < companyRows.size(); i++) {
                upByOne &= companyRows.get(i).get(column) instanceof Number now
                        && companyRows.get(i - 1).get(column) instanceof Number before
                        && now.longValue() - before.longValue() == 1;
            }
            if (upByOne) {
                counters.add(column);
            }
        }
        assertThat(items(s.customer().client().get("/api/portal/issues").expect(200))).hasSize(3);
        return new Layout(found, numberColumns.getFirst(), counters, fileName);
    }
}
