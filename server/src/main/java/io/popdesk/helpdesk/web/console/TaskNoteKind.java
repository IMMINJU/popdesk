package io.popdesk.helpdesk.web.console;

/** What an entry of a task's notes is (TASK-3, TASK-5). */
public enum TaskNoteKind {
    /** A note a person wrote. */
    NOTE,
    /** A field of the task changed; popdesk wrote the entry (S6B-AC-30). */
    CHANGE
}
