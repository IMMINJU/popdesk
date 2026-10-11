package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * One entry of an issue's history (S3-AC-62, S3-AC-64). A changed assignee is given as people in
 * {@code oldPerson} and {@code newPerson}; every other field as text in {@code oldValue} and
 * {@code newValue}.
 */
public record IssueHistoryEntry(
        @Schema(description = "Entries of an issue are in the order of their IDs.") long id,
        IssueHistoryKind kind,
        @Schema(description = "The field that changed: `STATUS`, `ASSIGNEE`, `TITLE`, `CONTENT`, or `PRIORITY`. "
                + "Later releases add names, so this is a plain string; show an entry with a name you do not "
                + "know as a change without detail. Null for `CREATED`.", nullable = true) String field,
        @Schema(description = "The value before the change: the name of the status or priority, or the title "
                + "or content as it was. Null for `CREATED`, for `ASSIGNEE`, and when there was no value.",
                nullable = true) String oldValue,
        @Schema(description = "The value after the change, like `oldValue`.", nullable = true) String newValue,
        @Schema(description = "For `ASSIGNEE`: the assignee before the change, by the account's current name; "
                + "null when there was none. Null for every other field.", nullable = true) Person oldPerson,
        @Schema(description = "For `ASSIGNEE`: the assignee after the change; null when it was cleared. Null "
                + "for every other field.", nullable = true) Person newPerson,
        IssueHistoryActor actor,
        OffsetDateTime createdAt) {}
