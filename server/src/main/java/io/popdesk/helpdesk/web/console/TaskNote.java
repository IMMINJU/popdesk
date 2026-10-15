package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * One entry of a task's notes: a note a person wrote ({@code NOTE}), or a change of one field of the
 * task ({@code CHANGE}, S6B-AC-30). A changed assignee is given as people in {@code oldPerson} and
 * {@code newPerson}, like the issue history; every other field as text in {@code oldValue} and
 * {@code newValue}. A deleted note stays in its place as a trace (COM-7, S6B-AC-22).
 */
public record TaskNote(
        @Schema(description = "Entries of a task are in the order of their IDs (S6B-AC-24).") long id,
        TaskNoteKind kind,
        @Schema(description = "Who wrote the note, or who made the change.") Person author,
        @Schema(description = "The note as written: plain text, 1 to 4,000 characters; line breaks are kept. "
                + "An empty string once the note is deleted (S6B-AC-22), and always for `CHANGE`.") String content,
        @Schema(description = "For `CHANGE`: the field that changed, `STATUS`, `ASSIGNEE`, `DUE_DATE`, or `TITLE` "
                + "(S6B-AC-30). Later releases may add names, so this is a plain string; show an entry with a "
                + "name you do not know as a change without detail. Null for `NOTE`.", nullable = true)
                String field,
        @Schema(description = "For `CHANGE`, the value before: for `STATUS` the status name, for `DUE_DATE` the "
                + "date `YYYY-MM-DD`, for `TITLE` the title as it was. Null for `ASSIGNEE`, for `NOTE`, and when "
                + "there was no value.", nullable = true) String oldValue,
        @Schema(description = "For `CHANGE`, the value after, like `oldValue`. Null when the value was cleared.",
                nullable = true) String newValue,
        @Schema(description = "For `ASSIGNEE`: the assignee before, by the account's current name; null when "
                + "there was none. Null for every other field.", nullable = true) Person oldPerson,
        @Schema(description = "For `ASSIGNEE`: the assignee after; null when it was cleared. Null for every "
                + "other field.", nullable = true) Person newPerson,
        OffsetDateTime createdAt,
        @Schema(description = "When the author last changed the note; null when it never changed (S6B-AC-21). "
                + "Always null for `CHANGE`.", nullable = true) OffsetDateTime editedAt,
        @Schema(description = "The note was deleted (S6B-AC-22). Always `false` for `CHANGE`.") boolean deleted,
        @Schema(nullable = true) OffsetDateTime deletedAt,
        @Schema(description = "The author, or an admin (TASK-3).", nullable = true) Person deletedBy) {}
