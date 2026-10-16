package io.popdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.DbInspector.Column;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tables of migration V20 (S8B-AC-27), read from the database: the names are the ones {@code design.md} 1절
 * gives, so this test reads the schema itself (the exception the slice allows for a migration). The values the
 * columns hold are seen through the API in the other 8b tests; the filling of {@code fr_settled_at} for issues
 * settled before V20 is seen by the upgrade sample ({@code deploy/upgrade}).
 */
class SlaAlertSchemaTest extends SlaAppTest {

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
    private List<String> checks(String table) {
        return db.constraints(table).values().stream().map(SlaAlertSchemaTest::plain).toList();
    }

    private static String plain(String definition) {
        return definition.replaceAll("[\\s()]", "").toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("S8B-AC-27 V20 adds to issue fr_alerted and res_alerted (text, WARNING or BREACHED) and "
            + "fr_settled_at (timestamptz), and the expression index issue_sla_alert_idx on live issues under SLA")
    void issueHasTheAlertColumnsAndIndex() {
        Map<String, String> issue = columns("issue");

        assertThat(issue).as("columns of issue").containsEntry("fr_alerted", "text null")
                .containsEntry("res_alerted", "text null")
                .containsEntry("fr_settled_at", "timestamp with time zone null");
        List<String> checks = checks("issue");
        for (String column : List.of("fr_alerted", "res_alerted")) {
            assertThat(checks).as("check constraints of issue on %s", column).anySatisfy(check -> assertThat(check)
                    .contains(column).contains("'warning'").contains("'breached'"));
        }
        String index = db.indexes("issue").get("issue_sla_alert_idx");
        assertThat(index).as("issue_sla_alert_idx").isNotNull();
        assertThat(plain(index)).as("definition of issue_sla_alert_idx")
                .contains("least", "fr_warn_at", "fr_due_at", "res_warn_at", "res_due_at", "fr_since", "res_since",
                        "fr_alerted", "res_alerted")
                .contains("wheredeleted_atisnullandsla_versionisnotnull");
    }

    @Test
    @DisplayName("S8B-AC-27 V20 adds the table sla_recalc_report with its counts that start at 0 and are never "
            + "negative, one open row per tenant (sla_recalc_report_open_idx), and three counts on notification")
    void summaryTableAndNotificationCounts() {
        Map<String, String> report = columns("sla_recalc_report");

        assertThat(report.keySet()).as("columns of sla_recalc_report").containsExactlyInAnyOrder("id", "tenant_id",
                "opened_at", "closed_at", "became_breached", "became_warning", "released");
        assertThat(report).as("columns of sla_recalc_report").containsEntry("tenant_id", "bigint not null")
                .containsEntry("opened_at", "timestamp with time zone not null")
                .containsEntry("closed_at", "timestamp with time zone null")
                .containsEntry("became_breached", "integer not null default 0")
                .containsEntry("became_warning", "integer not null default 0")
                .containsEntry("released", "integer not null default 0");
        assertThat(report.get("id")).as("id of sla_recalc_report").startsWith("bigint not null");
        List<String> checks = checks("sla_recalc_report");
        for (String column : List.of("became_breached", "became_warning", "released")) {
            assertThat(checks).as("check constraints of sla_recalc_report").contains(column + ">=0");
        }
        String open = db.indexes("sla_recalc_report").get("sla_recalc_report_open_idx");
        assertThat(open).as("sla_recalc_report_open_idx").isNotNull();
        assertThat(plain(open)).as("definition of sla_recalc_report_open_idx")
                .startsWith("createuniqueindex").contains("tenant_idwhereclosed_atisnull");

        Map<String, String> notification = columns("notification");
        assertThat(notification.entrySet().stream().filter(e -> e.getKey().startsWith("sla_"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .as("sla columns of notification").isEqualTo(Map.of("sla_became_breached", "integer null",
                        "sla_became_warning", "integer null", "sla_released", "integer null"));
    }
}
