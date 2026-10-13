package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * One entry of an issue's history (S3-AC-62, S3-AC-64). A changed assignee is given as people in
 * {@code oldPerson} and {@code newPerson}; every other field as text in {@code oldValue} and
 * {@code newValue}. An attachment that was attached or deleted is a change of the field
 * {@code ATTACHMENT} with the attachment's ID as the value (S5-AC-40, S5-AC-41).
 */
public record IssueHistoryEntry(
        @Schema(description = "Entries of an issue are in the order of their IDs.") long id,
        IssueHistoryKind kind,
        @Schema(description = "The field that changed: `STATUS`, `ASSIGNEE`, `TITLE`, `CONTENT`, `PRIORITY`, or "
                + "`ATTACHMENT` (a file or content image was attached to the issue or one of its comments, or "
                + "deleted; one entry for each attachment, S5-AC-40, S5-AC-41). "
                + "Later releases add names, so this is a plain string; show an entry with a name you do not "
                + "know as a change without detail. Null for `CREATED`.", nullable = true) String field,
        @Schema(description = "The value before the change: the name of the status or priority, or the title "
                + "or content as it was. For `ATTACHMENT`: the ID of the attachment that was deleted, never its "
                + "file name (S5-AC-43); null when one was attached. Null for `CREATED`, for `ASSIGNEE`, and "
                + "when there was no value.",
                nullable = true) String oldValue,
        @Schema(description = "The value after the change, like `oldValue`. For `ATTACHMENT`: the ID of the "
                + "attachment that was attached; null when one was deleted.", nullable = true) String newValue,
        @Schema(description = "For `ASSIGNEE`: the assignee before the change, by the account's current name; "
                + "null when there was none. Null for every other field.", nullable = true) Person oldPerson,
        @Schema(description = "For `ASSIGNEE`: the assignee after the change; null when it was cleared. Null "
                + "for every other field.", nullable = true) Person newPerson,
        IssueHistoryActor actor,
        OffsetDateTime createdAt) {}
