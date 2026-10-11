package io.popdesk.helpdesk.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/** What an account is still assigned to (ACCT-7). */
public record OpenAssignments(
        @Schema(description = "Issues that are not `CLOSED` and have this account as assignee.") long issues) {}
