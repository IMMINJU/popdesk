package io.popdesk.helpdesk.task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which fields of a task an edit changes, as the {@code CHANGE} entries it leaves (TASK-5, S6B-AC-30).
 * Pure: no clock, no I/O, no check of the values; the caller checked them first.
 */
public final class TaskChanges {

    /** A field a {@code CHANGE} entry names; stored in {@code task_note.field}, so the names never change. */
    public enum Field {
        STATUS,
        ASSIGNEE,
        DUE_DATE,
        TITLE
    }

    /**
     * A task's fields before the edit.
     *
     * @param title the title; not null
     * @param status {@code OPEN} or {@code DONE}; not null
     * @param assigneeId the assignee's account ID, or null for nobody
     * @param dueDate the due date, or null for none
     */
    public record Task(String title, TaskRepository.Status status, Long assigneeId, LocalDate dueDate) {}

    /**
     * What an edit sends. A field left out of the request keeps its value.
     *
     * @param title the new title, or null when it was left out
     * @param status the new status, or null when it was left out
     * @param assigneeSent whether the request has {@code assigneeId}
     * @param assigneeId the new assignee when it was sent; null clears it
     * @param dueDateSent whether the request has {@code dueDate}
     * @param dueDate the new due date when it was sent; null clears it
     */
    public record Edit(String title, TaskRepository.Status status, boolean assigneeSent, Long assigneeId,
            boolean dueDateSent, LocalDate dueDate) {}

    /**
     * One changed field and its values as {@code task_note} stores them: the status name, the account ID,
     * the date {@code YYYY-MM-DD}, or the title; null where there was or is no value.
     */
    public record Change(Field field, String oldValue, String newValue) {}

    private TaskChanges() {}

    /**
     * The changes an edit makes to a task.
     *
     * @param before the task before the edit
     * @param edit what the request sends
     * @return one {@link Change} for each field whose value differs after the edit, in the order
     *     {@code STATUS}, {@code ASSIGNEE}, {@code DUE_DATE}, {@code TITLE}; empty when every field sent
     *     has the value the task has, also when nothing is sent. A field left out is never in it. Clearing
     *     an assignee or a due date gives {@code newValue} null; setting one where there was none gives
     *     {@code oldValue} null. Titles are compared as they are, with their case
     */
    public static List<Change> of(Task before, Edit edit) {
        List<Change> changes = new ArrayList<>();
        if (edit.status() != null && edit.status() != before.status()) {
            changes.add(new Change(Field.STATUS, before.status().name(), edit.status().name()));
        }
        if (edit.assigneeSent() && !Objects.equals(edit.assigneeId(), before.assigneeId())) {
            changes.add(new Change(Field.ASSIGNEE, text(before.assigneeId()), text(edit.assigneeId())));
        }
        if (edit.dueDateSent() && !Objects.equals(edit.dueDate(), before.dueDate())) {
            changes.add(new Change(Field.DUE_DATE, text(before.dueDate()), text(edit.dueDate())));
        }
        if (edit.title() != null && !edit.title().equals(before.title())) {
            changes.add(new Change(Field.TITLE, before.title(), edit.title()));
        }
        return changes;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
