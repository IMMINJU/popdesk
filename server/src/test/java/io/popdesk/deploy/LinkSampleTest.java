package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 6c
 * (S6C-AC-60, ADR D-24): the next version's upgrade test starts from this sample, so it must hold links (a
 * duplicate one, a related one between issues of two companies), the history entries of a link made and of one
 * removed, and an inbox item about a linked issue. The dump is restored into a database of its own and read there,
 * like {@link TaskSampleTest}; the tables and columns are the ones the slice's design names ({@code design.md}
 * section 1).
 */
@Tag("deploy")
class LinkSampleTest {

    private final String project = "pdt-link-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S6C-AC-60 the sample of this version has a DUPLICATE_OF link with its duplicate, a RELATED link "
            + "between issues of two companies, LINK history entries of a link made and of one removed, and a "
            + "LINKED_ISSUE_RESOLVED or LINKED_ISSUE_CLOSED inbox item with a linked issue")
    void sampleHasLinksTheirHistoryAndALinkItem() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String schema = Db.quote(db.schema());
            assertThat(db.tables()).as("tables of the sample").contains("issue_link", "issue_history",
                    "notification");
            assertThat(columns(db, "notification")).as("columns of notification").contains("linked_issue_id");

            assertThat(db.count("select count(*) from " + schema + ".issue_link where type = 'DUPLICATE_OF' "
                    + "and duplicate_issue_id is not null")).as("DUPLICATE_OF links with their duplicate")
                    .isPositive();
            assertThat(db.count("select count(*) from " + schema + ".issue_link l "
                    + "join " + schema + ".issue lo on lo.id = l.low_issue_id "
                    + "join " + schema + ".issue hi on hi.id = l.high_issue_id "
                    + "where l.type = 'RELATED' and lo.company_id <> hi.company_id"))
                    .as("RELATED links between issues of two companies").isPositive();

            assertThat(db.count("select count(*) from " + schema + ".issue_history where field = 'LINK' "
                    + "and new_value is not null")).as("LINK entries of a link made").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".issue_history where field = 'LINK' "
                    + "and old_value is not null")).as("LINK entries of a link removed").isPositive();

            assertThat(db.count("select count(*) from " + schema + ".notification "
                    + "where kind in ('LINKED_ISSUE_RESOLVED', 'LINKED_ISSUE_CLOSED') "
                    + "and linked_issue_id is not null")).as("inbox items about a linked issue").isPositive();
        });
    }

    private static List<String> columns(Db db, String table) {
        return db.query("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = ?", db.schema(), table).stream().map(r -> (String) r.get("column_name")).toList();
    }
}
