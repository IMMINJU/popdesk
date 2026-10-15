package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * A link of an issue to another issue (LINK-1), as seen from the issue it is read on. A link has no ID of
 * its own: the two issues name it.
 */
public record IssueLink(
        @Schema(description = "Seen from this issue (S6C-AC-13).") IssueLinkType type,
        LinkedIssue issue,
        @Schema(description = "Who linked the two issues.") Person createdBy,
        OffsetDateTime createdAt) {}
