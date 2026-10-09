package io.popdesk.start;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code tenant_id} on the real schema, after all migrations ran, and in the rows the app writes. */
class TenantIdTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-103 every table of the migrated schema but the named exceptions has tenant_id bigint NOT NULL")
    void realSchemaKeepsTheRule() {
        Map<String, List<Column>> tables = TenantIdRule.read(db, db.schema());

        assertThat(tables.keySet()).as("tables of schema %s", db.schema()).contains("tenant");
        assertThat(tables.keySet().stream().filter(t -> !TenantIdRule.EXCEPTIONS.contains(t)).toList())
                .as("business tables of schema %s", db.schema()).isNotEmpty();
        assertThat(TenantIdRule.violations(tables)).as("tables breaking the tenant_id rule").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-103 this test, check-tenant-id.py, and server/README.md list the same exceptions")
    void exceptionListsAgree() {
        Path root = Repository.root();

        Set<String> script = scriptExceptions(read(root.resolve("scripts/ci/check-tenant-id.py")));
        Set<String> readme = readmeExceptions(read(root.resolve("server/README.md")));

        assertThat(script).as("EXEMPT_TABLES of check-tenant-id.py").isEqualTo(TenantIdRule.EXCEPTIONS);
        assertThat(readme).as("the Table | Reason table of server/README.md").isEqualTo(TenantIdRule.EXCEPTIONS);
    }

    @Test
    @DisplayName("S0-AC-05 every row the app writes in a business table has tenant_id 1")
    void rowsHaveTenantOne() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        fixtures.comment(customer, issue.key(), "A comment for tenant_id.");
        fixtures.attach(customer, issue.key(), File.text("tenant.txt", "tenant_id"));

        List<String> business = db.tables().stream().filter(t -> !TenantIdRule.EXCEPTIONS.contains(t)).toList();
        List<String> withoutColumn = business.stream()
                .filter(t -> db.columns(t).stream().noneMatch(c -> c.name().equals("tenant_id")))
                .toList();
        assertThat(withoutColumn).as("business tables without tenant_id").isEmpty();

        long rows = 0;
        List<String> otherTenant = new ArrayList<>();
        for (String table : business) {
            rows += db.count("select count(*) from \"" + table + "\"");
            if (db.count("select count(*) from \"" + table + "\" where tenant_id is distinct from 1") > 0) {
                otherTenant.add(table);
            }
        }
        assertThat(rows).as("rows in business tables").isPositive();
        assertThat(otherTenant).as("tables with a row whose tenant_id is not 1").isEmpty();
    }

    /** The names in {@code EXEMPT_TABLES = {...}} of the script. */
    static Set<String> scriptExceptions(String script) {
        Matcher block = Pattern.compile("EXEMPT_TABLES\\s*=\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(script);
        assertThat(block.find()).as("EXEMPT_TABLES in check-tenant-id.py").isTrue();
        Set<String> names = new LinkedHashSet<>();
        Matcher name = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1).replaceAll("#[^\\n]*", ""));
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    /**
     * The first cells of the Markdown table whose header is {@code | Table | Reason |}. Every row
     * must give a reason.
     */
    static Set<String> readmeExceptions(String readme) {
        Set<String> names = new LinkedHashSet<>();
        boolean inTable = false;
        for (String line : readme.lines().toList()) {
            String trimmed = line.strip();
            List<String> cells = cells(trimmed);
            if (cells.size() >= 2 && cells.get(0).equalsIgnoreCase("Table") && cells.get(1).equalsIgnoreCase("Reason")) {
                inTable = true;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (!trimmed.startsWith("|")) {
                break;
            }
            if (cells.get(0).matches("[-:]+")) {
                continue;
            }
            assertThat(cells.size() >= 2 && !cells.get(1).isBlank()).as("a reason in README row %s", trimmed).isTrue();
            names.add(cells.get(0).replace("`", ""));
        }
        return names;
    }

    private static List<String> cells(String line) {
        if (!line.startsWith("|")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String cell : line.substring(1).split("\\|", -1)) {
            out.add(cell.strip());
        }
        if (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
