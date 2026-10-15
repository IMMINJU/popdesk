package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * One entry of an issue's history (S3-AC-62, S3-AC-64). A changed assignee is given as people in
 * {@code oldPerson} and {@code newPerson}; every other field as text in {@code oldValue} and
 * {@code newValue}. An attachment that was attached or deleted is a change of the field
 * {@code ATTACHMENT} with the attachment's ID as the value (S5-AC-40, S5-AC-41). A label put on or
 * taken off is a change of {@code LABEL} with the label's name at that time (S6A-AC-60); a due date,
 * of {@code DUE_DATE} with the dates (S6A-AC-61). A link made or removed is a change of {@code LINK}
 * given as links in {@code oldLink} and {@code newLink}, without text values (S6C-AC-21). Deleting,
 * restoring, and purging the issue is a change of {@code TRASH} with {@code TRASHED} and
 * {@code PURGED} as values (S7A-AC-06).
 */
public record IssueHistoryEntry(
        @Schema(description = "Entries of an issue are in the order of their IDs.") long id,
        IssueHistoryKind kind,
        @Schema(description = "The field that changed: `STATUS`, `ASSIGNEE`, `TITLE`, `CONTENT`, `PRIORITY`, or "
                + "`ATTACHMENT` (a file or content image was attached to the issue or one of its comments, or "
                + "deleted; one entry for each attachment, S5-AC-40, S5-AC-41), `LABEL` (a label was put on or "
                + "taken off; one entry for each label, S6A-AC-60), `DUE_DATE` (the due date was set, changed, "
                + "or cleared, S6A-AC-61), or `LINK` (a link to another issue was made or removed; the same "
                + "change is an entry of the other issue too, S6C-AC-20). A `LINK` entry whose other issue the "
                + "caller cannot see is left out, also from the count of pages, and is back when the caller can "
                + "see it again (S6C-AC-22). `TRASH`: the issue was deleted into the trash (new value "
                + "`TRASHED`), restored from it (old value `TRASHED`, new value null), or purged (old value "
                + "`TRASHED`, new value `PURGED`); the actor is the admin, or popdesk itself for a purge after "
                + "30 days (S7A-AC-06). "
                + "Later releases add names, so this is a plain string; show an entry with a name you do not "
                + "know as a change without detail. Null for `CREATED`.", nullable = true) String field,
        @Schema(description = "The value before the change: the name of the status or priority, or the title "
                + "or content as it was. For `ATTACHMENT`: the ID of the attachment that was deleted, never its "
                + "file name (S5-AC-43); null when one was attached. For `LABEL`: the name the label had when it "
                + "was taken off, not changed by a later rename (S6A-AC-05); null when one was put on. For "
                + "`DUE_DATE`: the date before, `YYYY-MM-DD`; null when there was none. Null for `CREATED`, for "
                + "`ASSIGNEE`, for `LINK`, and "
                + "when there was no value. For `TRASH`: `TRASHED` on a restore and a purge, null on a delete. "
                + "`TITLE` and `CONTENT` entries of a purged issue have both values null (S7A-AC-30).",
                nullable = true) String oldValue,
        @Schema(description = "The value after the change, like `oldValue`. For `ATTACHMENT`: the ID of the "
                + "attachment that was attached; null when one was deleted. For `LABEL`: the name of the label "
                + "that was put on; null when one was taken off. For `DUE_DATE`: the date after, `YYYY-MM-DD`; "
                + "null when it was cleared. For `TRASH`: `TRASHED` on a delete, `PURGED` on a purge, null on a "
                + "restore.", nullable = true) String newValue,
        @Schema(description = "For `ASSIGNEE`: the assignee before the change, by the account's current name; "
                + "null when there was none. Null for every other field.", nullable = true) Person oldPerson,
        @Schema(description = "For `ASSIGNEE`: the assignee after the change; null when it was cleared. Null "
                + "for every other field.", nullable = true) Person newPerson,
        IssueHistoryActor actor,
        OffsetDateTime createdAt,
        @Schema(description = "For `LINK`: the link that was removed, seen from this issue, with the other "
                + "issue's current key and title (S6C-AC-21); null when one was made. Null for every other "
                + "field.", nullable = true) IssueHistoryLink oldLink,
        @Schema(description = "For `LINK`: the link that was made, like `oldLink`; null when one was removed. "
                + "Null for every other field.", nullable = true) IssueHistoryLink newLink) {}
