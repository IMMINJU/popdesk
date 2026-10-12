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
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 4
 * (S4-AC-73, ADR D-24): the next version's upgrade test starts from this sample, so it must hold a
 * mention, an edited comment, a deleted comment, and inbox items about issues, read and unread. The
 * dump is restored into a database of its own and read there, like {@link IssueSampleTest}; the
 * tables and columns are the ones the slice's design names ({@code design.md} sections 0 and 1).
 */
@Tag("deploy")
class TalkSampleTest {

    private final String project = "pdt-talk-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S4-AC-73 the sample of this version has a row in the mention table, an edited comment, a "
            + "deleted comment with an empty content, and inbox items about an issue, one read and one unread")
    void sampleHasAMentionEditedAndDeletedCommentsAndIssueItems() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String schema = Db.quote(db.schema());
            assertThat(db.tables()).as("tables of the sample").contains("comment", "comment_mention", "notification");
            assertThat(db.count("select count(*) from " + schema + ".comment_mention"))
                    .as("rows in comment_mention").isPositive();

            assertThat(columns(db, "comment")).as("columns of comment")
                    .contains("edited_at", "deleted_at", "deleted_by");
            assertThat(db.count("select count(*) from " + schema + ".comment where edited_at is not null "
                    + "and deleted_at is null")).as("edited comments that are not deleted").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".comment where deleted_at is not null "
                    + "and deleted_by is not null and content = ''"))
                    .as("deleted comments, with who deleted them and an empty content").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".comment c where c.deleted_at is null and exists "
                    + "(select 1 from " + schema + ".comment_mention m where m.comment_id = c.id)"))
                    .as("comments that mention someone and are not deleted").isPositive();

            assertThat(columns(db, "notification")).as("columns of notification")
                    .contains("issue_id", "comment_id", "actor_id");
            assertThat(db.count("select count(*) from " + schema + ".notification where issue_id is not null "
                    + "and read_at is not null")).as("read inbox items about an issue").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".notification where issue_id is not null "
                    + "and read_at is null")).as("unread inbox items about an issue").isPositive();
        });
    }

    private static List<String> columns(Db db, String table) {
        return db.query("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = ?", db.schema(), table).stream().map(r -> (String) r.get("column_name")).toList();
    }
}
