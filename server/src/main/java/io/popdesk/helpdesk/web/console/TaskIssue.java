package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.IssueStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** The issue a task of the caller's own tasks belongs to. */
public record TaskIssue(
        @Schema(description = "`{company code}-{number}`, for example `ACME-7`.") String key,
        @Schema(description = "The title as it is now.") String title,
        IssueStatus status) {}
