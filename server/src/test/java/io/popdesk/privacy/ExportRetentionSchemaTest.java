package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tables and columns of migration V23 (S10B-AC-28, D-18), read from the database: the names are the ones
 * {@code design.md} 1절 gives, so this test reads the schema itself (the exception the slice allows for a migration),
 * as {@code sla.SlaAlertSchemaTest} does. What the columns hold is seen through the API in the other 10b tests.
 */
class ExportRetentionSchemaTest extends BareAppTest {

    /** Column name to "data type, nullable, default" of a table. */
    private Map<String, String> columns(String table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Column c : db.columns(table)) {
            out.put(c.name(), c.dataType() + (c.nullable() ? " null" : " not null")
                    + (c.defaultValue() == null ? "" : " default " + c.defaultValue()));
        }
        return out;
    }

    /** Constraint definitions of a table, without the spaces and parentheses PostgreSQL adds. */
    private List<String> constraints(String table) {
        return db.constraints(table).values().stream().map(ExportRetentionSchemaTest::plain).toList();
    }

    private static String plain(String definition) {
        return definition.replaceAll("[\\s()]", "").toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("S10B-AC-28 V23 adds the table company_export with its columns, its four states, its keys to company "
            + "and account, and one RUNNING export per tenant (company_export_running_key)")
    void companyExportTable() {
        assertThat(db.tables()).as("tables").contains("company_export");
        Map<String, String> export = columns("company_export");

        assertThat(export).as("columns of company_export").isEqualTo(Map.ofEntries(
                Map.entry("id", "uuid not null"),
                Map.entry("tenant_id", "bigint not null"),
                Map.entry("company_id", "bigint not null"),
                Map.entry("requested_by", "bigint not null"),
                Map.entry("include_internal", "boolean not null"),
                Map.entry("include_private", "boolean not null"),
                Map.entry("state", "text not null"),
                Map.entry("failure", "text null"),
                Map.entry("size_bytes", "bigint null"),
                Map.entry("requested_at", "timestamp with time zone not null"),
                Map.entry("finished_at", "timestamp with time zone null")));
        List<String> constraints = constraints("company_export");
        assertThat(constraints).as("constraints of company_export")
                .anySatisfy(c -> assertThat(c).startsWith("primarykey").contains("id"))
                .anySatisfy(c -> assertThat(c).contains("state").contains("'running'").contains("'done'")
                        .contains("'failed'").contains("'expired'"))
                .anySatisfy(c -> assertThat(c).startsWith("foreignkeycompany_id").contains("referencescompanyid"))
                .anySatisfy(c -> assertThat(c).startsWith("foreignkeyrequested_by").contains("referencesaccountid"));
        String running = db.indexes("company_export").get("company_export_running_key");
        assertThat(running).as("company_export_running_key").isNotNull();
        assertThat(plain(running)).as("definition of company_export_running_key").startsWith("createuniqueindex")
                .contains("tenant_idwherestate='running'");
    }

    @Test
    @DisplayName("S10B-AC-28 V23 adds the table issue_retention with one row, id 1, whose id can be nothing but 1")
    void issueRetentionTable() {
        assertThat(db.tables()).as("tables").contains("issue_retention");
        Map<String, String> state = columns("issue_retention");

        assertThat(state).as("columns of issue_retention").isEqualTo(Map.of(
                "id", "smallint not null",
                "tenant_id", "bigint not null",
                "period_seconds", "bigint null",
                "moves_from", "timestamp with time zone null",
                "last_moved_at", "timestamp with time zone null"));
        assertThat(constraints("issue_retention")).as("constraints of issue_retention")
                .anySatisfy(c -> assertThat(c).startsWith("primarykey").contains("id"))
                .anySatisfy(c -> assertThat(c).startsWith("check").contains("id=1"));
        assertThat(db.rows("select id, tenant_id from issue_retention")).as("rows of issue_retention")
                .extracting(r -> ((Number) r.get("id")).longValue() + "/" + ((Number) r.get("tenant_id")).longValue())
                .containsExactly("1/1");
    }

    @Test
    @DisplayName("S10B-AC-28 V23 adds issue.restored_at, the partial index issue_closed_for_retention on closed_at of "
            + "CLOSED issues not in the trash, and notification.retention_count and retention_days")
    void issueAndNotificationColumns() {
        assertThat(columns("issue")).as("columns of issue")
                .containsEntry("restored_at", "timestamp with time zone null");
        String index = db.indexes("issue").get("issue_closed_for_retention");
        assertThat(index).as("issue_closed_for_retention").isNotNull();
        assertThat(plain(index)).as("definition of issue_closed_for_retention").contains("closed_atwhere")
                .contains("status='closed'").contains("deleted_atisnull");

        assertThat(columns("notification").entrySet().stream().filter(e -> e.getKey().startsWith("retention_"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .as("retention columns of notification")
                .isEqualTo(Map.of("retention_count", "integer null", "retention_days", "integer null"));
    }
}
