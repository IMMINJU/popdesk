package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 3
 * (S3-AC-90, ADR D-24): the next version's upgrade test starts from this sample, so it must hold
 * issues that are past {@code RECEIVED}, an assignee, an issue staff filed for a customer, a changed
 * field in the history, and a filing key. The dump is restored into a database of its own and read
 * there, like {@code UpgradeHarnessTest#sampleHasBoundaryValues}; the tables and columns are the
 * ones the slice's design names ({@code design.md} section 1).
 */
@Tag("deploy")
class IssueSampleTest {

    private final String project = "pdt-issue-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S3-AC-90 the sample of this version has two or more issues past RECEIVED, one of them CLOSED, an "
            + "issue with an assignee, an issue filed on behalf, a CHANGED history entry, and a filing key")
    void sampleHasIssuesPastReceivedAnAssigneeAndAKey() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String schema = Db.quote(db.schema());
            assertThat(db.tables()).as("tables of the sample").contains("issue");
            List<String> statuses = db.query("select status::text as status from " + schema + ".issue").stream()
                    .map(r -> ((String) r.get("status")).toUpperCase(Locale.ROOT)).toList();
            assertThat(statuses).as("statuses of the sample's issues").isNotEmpty();
            assertThat(statuses.stream().filter(s -> !s.equals("RECEIVED")).toList())
                    .as("statuses other than RECEIVED among %s", statuses).hasSizeGreaterThanOrEqualTo(2)
                    .contains("CLOSED");

            assertThat(columns(db, "issue")).as("columns of issue").contains("assignee_id", "created_by");
            assertThat(db.count("select count(*) from " + schema + ".issue where assignee_id is not null"))
                    .as("issues with an assignee").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".issue where created_by is not null"))
                    .as("issues staff filed for a customer (created_by)").isPositive();

            assertThat(db.tables()).as("tables of the sample").contains("issue_history", "issue_idempotency");
            assertThat(db.count("select count(*) from " + schema + ".issue_history where kind = 'CHANGED'"))
                    .as("CHANGED entries in issue_history").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".issue_idempotency"))
                    .as("rows in issue_idempotency").isPositive();
        });
    }

    private static List<String> columns(Db db, String table) {
        return db.query("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = ?", db.schema(), table).stream().map(r -> (String) r.get("column_name")).toList();
    }
}
