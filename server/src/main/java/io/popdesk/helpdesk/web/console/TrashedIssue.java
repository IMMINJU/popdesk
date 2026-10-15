package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.IssueStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** An issue in the trash, as admins see it in the trash list (S7A-AC-20, TRASH-2). */
public record TrashedIssue(
        String key,
        @Schema(description = "The title as it was when the issue was deleted.") String title,
        String companyCode,
        String companyName,
        @Schema(description = "The status the issue had when it was deleted; the trash does not change it.")
                IssueStatus status,
        OffsetDateTime deletedAt,
        @Schema(description = "The admin who deleted the issue, by the account's current name.") Person deletedBy,
        @Schema(description = "When popdesk purges the issue by itself: `deletedAt` plus 30 days, measured in UTC "
                + "(S7A-AC-31). The hourly job may do it up to an hour later.") OffsetDateTime purgeAt) {}
