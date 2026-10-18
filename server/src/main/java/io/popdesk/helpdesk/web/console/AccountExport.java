package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The file {@code exportAccount} streams (PII-3, S10A-AC-01): one account and what it wrote. It holds no
 * other account's ID, name, or email (S10A-AC-05). The fields come in this order.
 */
public record AccountExport(
        @Schema(description = "When the export started.") OffsetDateTime exportedAt,
        AccountExportAccount account,
        @Schema(description = "Null without a profile photo, and for an anonymized account (S10A-AC-07).",
                nullable = true) AccountExportPhoto photo,
        @Schema(description = "Issues the account is the reporter of, filed on its behalf included; not the ones "
                + "it filed for someone else. Issues in the trash are in it, purged ones are not (S10A-AC-02). "
                + "Oldest first (`createdAt`, then ID).") List<AccountExportIssue> issues,
        @Schema(description = "Comments the account wrote and did not delete, internal ones included, on issues "
                + "that are not purged (S10A-AC-03). Oldest first (`createdAt`, then ID).")
                List<AccountExportComment> comments,
        @Schema(description = "Files the account uploaded and did not delete, without their content; temporary "
                + "images not attached yet are left out (S10A-AC-04). Oldest first "
                + "(`createdAt`, then ID).")
                List<AccountExportAttachment> attachments) {}
