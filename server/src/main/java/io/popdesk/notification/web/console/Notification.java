package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An item of the caller's inbox. */
public record Notification(
        long id,
        NotificationKind kind,
        @Schema(description = "The issue the item is about, as it is now; null for `DELIVERY_FAILED`, "
                + "`ATTACHMENT_QUOTA_WARNING`, `MAIL_STALLED`, `WEBHOOK_STALLED`, `AUDIT_FORWARD_STALLED`, "
                + "`CLEANUP_STALLED`, `MAIL_IN_STALLED`, `MAIL_IN_QUARANTINED` (S12-AC-37), "
                + "`MEMBERSHIP_REQUESTED` (S4-AC-40), `SLA_RECALCULATED` (S8B-AC-15), "
                + "`COMPANY_EXPORT_READY`, `COMPANY_EXPORT_FAILED`, and `ISSUE_RETENTION_ARMED` (S10B-AC-27).",
                nullable = true)
                NotificationIssue issue,
        @Schema(description = "The company of the issue, or the company a `MEMBERSHIP_REQUESTED`, "
                + "`COMPANY_EXPORT_READY`, or `COMPANY_EXPORT_FAILED` item is about; null for the other kinds.",
                nullable = true) String companyCode,
        @Schema(description = "The comment the item is about; null when it is about no comment.", nullable = true)
                Long commentId,
        @Schema(description = "Who did it; null for an item about no issue, and null for what popdesk itself "
                + "did, such as closing a `RESOLVED` issue after 7 days (S7A-AC-43).", nullable = true)
                NotificationActor actor,
        OffsetDateTime createdAt,
        @Schema(description = "When the caller marked it read.", nullable = true) OffsetDateTime readAt,
        @Schema(description = "The task a `TASK_ASSIGNED` item is about, with its title as it is now; null for "
                + "the other kinds (S6B-AC-33). Deleting the task deletes its items (S6B-AC-36).", nullable = true)
                NotificationTask task,
        @Schema(description = "For `LINKED_ISSUE_RESOLVED` and `LINKED_ISSUE_CLOSED`: the linked issue that was "
                + "resolved or closed, with its key and title as they are now; `issue` is the caller's own issue "
                + "it is linked to (S6C-AC-31). Such an item is listed only while the caller can see both issues "
                + "(S6C-AC-33). Null for the other kinds (S6C-AC-34).", nullable = true)
                NotificationIssue linkedIssue,
        @Schema(description = "For `SLA_RECALCULATED`: how many issues working the SLA due times out again "
                + "made overdue, due soon, or no longer either, counted as changes to an issue (S8B-AC-12). "
                + "Null for the other kinds (S8B-AC-15).", nullable = true)
                SlaSummary slaSummary,
        @Schema(description = "For `ISSUE_RETENTION_ARMED`: how many issues automatic cleanup would move to the "
                + "trash when the item was written (S10B-AC-19). Null for the other kinds.", nullable = true)
                Integer retentionCount,
        @Schema(description = "For `ISSUE_RETENTION_ARMED`: `POPDESK_ISSUE_RETENTION` in whole days, rounded "
                + "down (S10B-AC-19). Null for the other kinds.", nullable = true)
                Integer retentionDays) {}
