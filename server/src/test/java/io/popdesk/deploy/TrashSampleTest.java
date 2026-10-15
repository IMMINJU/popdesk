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
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 7a
 * (S7A-AC-60, ADR D-24): the next version's upgrade test starts from this sample, so it must hold an issue in the
 * trash, a purged issue, and a {@code RESOLVED} issue with its time to close from. The dump is restored into a
 * database of its own and read there, like {@link LinkSampleTest}; the columns are the ones the slice's design names
 * ({@code design.md} section 1).
 */
@Tag("deploy")
class TrashSampleTest {

    private final String project = "pdt-trash-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S7A-AC-60 the sample of this version has an issue in the trash with who deleted it, a purged issue "
            + "with an empty title and content, and a live RESOLVED issue with its time to close from")
    void sampleHasTrashedPurgedAndResolvedIssues() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String issue = Db.quote(db.schema()) + ".issue";
            assertThat(columns(db, "issue")).as("columns of issue").contains("deleted_at", "deleted_by",
                    "purged_at", "auto_close_from");

            assertThat(db.count("select count(*) from " + issue + " where deleted_at is not null "
                    + "and deleted_by is not null and purged_at is null and title <> ''"))
                    .as("issues in the trash, with who deleted them").isPositive();
            assertThat(db.count("select count(*) from " + issue + " where purged_at is not null "
                    + "and deleted_at is not null and title = '' and content = ''"))
                    .as("purged issues, emptied").isPositive();
            assertThat(db.count("select count(*) from " + issue + " where status = 'RESOLVED' "
                    + "and auto_close_from is not null and deleted_at is null"))
                    .as("live RESOLVED issues with their time to close from").isPositive();
        });
    }

    private static List<String> columns(Db db, String table) {
        return db.query("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = ?", db.schema(), table).stream().map(r -> (String) r.get("column_name")).toList();
    }
}
