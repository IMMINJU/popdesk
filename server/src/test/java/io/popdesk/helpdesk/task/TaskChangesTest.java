package io.popdesk.helpdesk.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.helpdesk.task.TaskChanges.Change;
import io.popdesk.helpdesk.task.TaskChanges.Edit;
import io.popdesk.helpdesk.task.TaskChanges.Field;
import io.popdesk.helpdesk.task.TaskChanges.Task;
import io.popdesk.helpdesk.task.TaskRepository.Status;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which fields of a task an edit changes (S6B-AC-30). Written from the Javadoc of {@link TaskChanges}
 * alone.
 */
class TaskChangesTest {

    private static final LocalDate DUE = LocalDate.of(2026, 10, 20);
    private static final LocalDate LATER = LocalDate.of(2027, 1, 5);

    private static final Task OPEN_TASK = new Task("Call back", Status.OPEN, 7L, DUE);
    private static final Task BARE_TASK = new Task("Call back", Status.OPEN, null, null);

    private static Edit nothing() {
        return new Edit(null, null, false, null, false, null);
    }

    private static Edit status(Status status) {
        return new Edit(null, status, false, null, false, null);
    }

    private static Edit assignee(Long id) {
        return new Edit(null, null, true, id, false, null);
    }

    private static Edit dueDate(LocalDate date) {
        return new Edit(null, null, false, null, true, date);
    }

    private static Edit title(String title) {
        return new Edit(title, null, false, null, false, null);
    }

    // ---- nothing changes ----

    @Test
    @DisplayName("S6B-AC-30 an edit that sends nothing changes nothing")
    void nothingSent() {
        assertThat(TaskChanges.of(OPEN_TASK, nothing())).isEmpty();
        assertThat(TaskChanges.of(BARE_TASK, nothing())).isEmpty();
    }

    @Test
    @DisplayName("S6B-AC-30 sending every field with the value it has changes nothing")
    void sameValues() {
        assertThat(TaskChanges.of(OPEN_TASK, new Edit("Call back", Status.OPEN, true, 7L, true, DUE)))
                .isEmpty();
        assertThat(TaskChanges.of(BARE_TASK, new Edit("Call back", Status.OPEN, true, null, true, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("S6B-AC-30 clearing what is already empty changes nothing")
    void clearingEmpty() {
        assertThat(TaskChanges.of(BARE_TASK, assignee(null))).isEmpty();
        assertThat(TaskChanges.of(BARE_TASK, dueDate(null))).isEmpty();
    }

    @Test
    @DisplayName("S6B-AC-30 a field left out is never in the list, whatever value rides with it")
    void leftOutIgnored() {
        assertThat(TaskChanges.of(OPEN_TASK, new Edit(null, null, false, 9L, false, LATER))).isEmpty();
        assertThat(TaskChanges.of(OPEN_TASK, new Edit(null, null, false, null, false, null))).isEmpty();
    }

    // ---- status ----

    @Test
    @DisplayName("S6B-AC-30 finishing a task is one STATUS change by name")
    void statusDone() {
        assertThat(TaskChanges.of(OPEN_TASK, status(Status.DONE)))
                .containsExactly(new Change(Field.STATUS, "OPEN", "DONE"));
    }

    @Test
    @DisplayName("S6B-AC-30 reopening a done task is one STATUS change back")
    void statusReopened() {
        Task done = new Task("Call back", Status.DONE, 7L, DUE);
        assertThat(TaskChanges.of(done, status(Status.OPEN)))
                .containsExactly(new Change(Field.STATUS, "DONE", "OPEN"));
        assertThat(TaskChanges.of(done, status(Status.DONE))).isEmpty();
    }

    // ---- assignee ----

    @Test
    @DisplayName("S6B-AC-30 another assignee is one ASSIGNEE change by account ID")
    void assigneeChanged() {
        assertThat(TaskChanges.of(OPEN_TASK, assignee(9L)))
                .containsExactly(new Change(Field.ASSIGNEE, "7", "9"));
    }

    @Test
    @DisplayName("S6B-AC-30 clearing the assignee gives newValue null")
    void assigneeCleared() {
        assertThat(TaskChanges.of(OPEN_TASK, assignee(null)))
                .containsExactly(new Change(Field.ASSIGNEE, "7", null));
    }

    @Test
    @DisplayName("S6B-AC-30 setting an assignee where there was none gives oldValue null")
    void assigneeSet() {
        assertThat(TaskChanges.of(BARE_TASK, assignee(9L)))
                .containsExactly(new Change(Field.ASSIGNEE, null, "9"));
    }

    // ---- due date ----

    @Test
    @DisplayName("S6B-AC-30 another due date is one DUE_DATE change as YYYY-MM-DD")
    void dueDateChanged() {
        assertThat(TaskChanges.of(OPEN_TASK, dueDate(LATER)))
                .containsExactly(new Change(Field.DUE_DATE, "2026-10-20", "2027-01-05"));
    }

    @Test
    @DisplayName("S6B-AC-30 clearing the due date gives newValue null")
    void dueDateCleared() {
        assertThat(TaskChanges.of(OPEN_TASK, dueDate(null)))
                .containsExactly(new Change(Field.DUE_DATE, "2026-10-20", null));
    }

    @Test
    @DisplayName("S6B-AC-30 setting a due date where there was none gives oldValue null")
    void dueDateSet() {
        assertThat(TaskChanges.of(BARE_TASK, dueDate(LATER)))
                .containsExactly(new Change(Field.DUE_DATE, null, "2027-01-05"));
    }

    // ---- title ----

    @Test
    @DisplayName("S6B-AC-30 another title is one TITLE change")
    void titleChanged() {
        assertThat(TaskChanges.of(OPEN_TASK, title("Call back today")))
                .containsExactly(new Change(Field.TITLE, "Call back", "Call back today"));
    }

    @Test
    @DisplayName("S6B-AC-30 titles are compared with their case")
    void titleCase() {
        assertThat(TaskChanges.of(OPEN_TASK, title("call back")))
                .containsExactly(new Change(Field.TITLE, "Call back", "call back"));
        assertThat(TaskChanges.of(OPEN_TASK, title("Call back"))).isEmpty();
    }

    // ---- together ----

    @Test
    @DisplayName("S6B-AC-30 two fields at once are two changes, STATUS before TITLE")
    void twoTogether() {
        assertThat(TaskChanges.of(OPEN_TASK, new Edit("Call back today", Status.DONE, false, null, false, null)))
                .containsExactly(
                        new Change(Field.STATUS, "OPEN", "DONE"),
                        new Change(Field.TITLE, "Call back", "Call back today"));
    }

    @Test
    @DisplayName("S6B-AC-30 assignee and due date cleared together come in the order ASSIGNEE, DUE_DATE")
    void clearedTogether() {
        assertThat(TaskChanges.of(OPEN_TASK, new Edit(null, null, true, null, true, null)))
                .containsExactly(
                        new Change(Field.ASSIGNEE, "7", null),
                        new Change(Field.DUE_DATE, "2026-10-20", null));
    }

    @Test
    @DisplayName("S6B-AC-30 every field changed comes in the order STATUS, ASSIGNEE, DUE_DATE, TITLE")
    void allFour() {
        Task done = new Task("Call back", Status.DONE, 7L, DUE);
        assertThat(TaskChanges.of(done, new Edit("Write up", Status.OPEN, true, 9L, true, LATER)))
                .containsExactly(
                        new Change(Field.STATUS, "DONE", "OPEN"),
                        new Change(Field.ASSIGNEE, "7", "9"),
                        new Change(Field.DUE_DATE, "2026-10-20", "2027-01-05"),
                        new Change(Field.TITLE, "Call back", "Write up"));
    }

    @Test
    @DisplayName("S6B-AC-30 only the fields that differ are listed when others are sent unchanged")
    void someSame() {
        assertThat(TaskChanges.of(OPEN_TASK, new Edit("Call back", Status.OPEN, true, 7L, true, LATER)))
                .containsExactly(new Change(Field.DUE_DATE, "2026-10-20", "2027-01-05"));
    }
}
