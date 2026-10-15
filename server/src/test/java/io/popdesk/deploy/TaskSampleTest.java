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
 * What the sample generator of this version ({@code deploy/upgrade/make-sample.py}) leaves of slice 6b
 * (S6B-AC-95, ADR D-24): the next version's upgrade test starts from this sample, so it must hold tasks (an
 * OPEN one with an assignee, a DONE one, a deleted one), notes (an edited one, a deleted one), changes, and an
 * inbox item about a task. The dump is restored into a database of its own and read there, like
 * {@link TalkSampleTest}; the tables and columns are the ones the slice's design names ({@code design.md}
 * section 1).
 */
@Tag("deploy")
class TaskSampleTest {

    private final String project = "pdt-task-sample-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        IssueInstallSteps.removeProject(project);
    }

    @Test
    @DisplayName("S6B-AC-95 the sample of this version has an OPEN task with an assignee, a DONE task, a deleted "
            + "task with an empty title, an edited note, a deleted note with an empty content, a change, and a "
            + "TASK_ASSIGNED inbox item and a task in the inbox and send queue columns")
    void sampleHasTasksNotesChangesAndATaskItem() {
        Path sample = IssueInstallSteps.makeSample(project);

        IssueInstallSteps.withRestored(sample.resolve("sample.dump"), db -> {
            String schema = Db.quote(db.schema());
            assertThat(db.tables()).as("tables of the sample").contains("task", "task_note", "notification",
                    "outbox");
            assertThat(columns(db, "notification")).as("columns of notification").contains("task_id");
            assertThat(columns(db, "outbox")).as("columns of outbox").contains("task_id");

            assertThat(db.count("select count(*) from " + schema + ".task where status = 'OPEN' "
                    + "and assignee_id is not null and deleted_at is null")).as("OPEN tasks with an assignee")
                    .isPositive();
            assertThat(db.count("select count(*) from " + schema + ".task where status = 'DONE' "
                    + "and done_at is not null and deleted_at is null")).as("DONE tasks").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".task where deleted_at is not null "
                    + "and deleted_by is not null and title = ''")).as("deleted tasks with an empty title")
                    .isPositive();

            assertThat(db.count("select count(*) from " + schema + ".task_note where kind = 'NOTE' "
                    + "and edited_at is not null and deleted_at is null")).as("edited notes that are not deleted")
                    .isPositive();
            assertThat(db.count("select count(*) from " + schema + ".task_note where kind = 'NOTE' "
                    + "and deleted_at is not null and deleted_by is not null and content = ''"))
                    .as("deleted notes, with who deleted them and an empty content").isPositive();
            assertThat(db.count("select count(*) from " + schema + ".task_note where kind = 'CHANGE' "
                    + "and field is not null")).as("changes").isPositive();

            assertThat(db.count("select count(*) from " + schema + ".notification n where n.kind = 'TASK_ASSIGNED' "
                    + "and exists (select 1 from " + schema + ".task t where t.id = n.task_id "
                    + "and t.deleted_at is null)")).as("TASK_ASSIGNED inbox items about a task that is there")
                    .isPositive();
        });
    }

    private static List<String> columns(Db db, String table) {
        return db.query("select column_name from information_schema.columns where table_schema = ? "
                + "and table_name = ?", db.schema(), table).stream().map(r -> (String) r.get("column_name")).toList();
    }
}
